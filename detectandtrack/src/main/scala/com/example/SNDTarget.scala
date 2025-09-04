package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import scala.io.Source._
import scala.concurrent.duration._

trait TargetD extends SensorEvent
case class TargetAck(id:Int, ObsX:Double, ObsY: Double) extends TargetD
case class TargetDataObs(id: Int, data: Matrix2x2, sender: ActorRef[SensorEvent]) extends TargetD
case class KalmanEstimate(dataPos: Matrix2x2, dataVel: Matrix2x2) extends TargetD

// New message types for batch processing
case class BatchObservations(observations: List[(Int, Matrix2x2)], sender: ActorRef[SensorEvent]) extends TargetD
case class ClosestObservationResult(targetId: Int, selectedObsId: Int, distance: Double, position: Matrix2x2) extends TargetD

// New cleanup messages
case object CheckTargetActivity extends TargetD
case class TargetCleanupNotification(targetId: Int, reason: String) extends SensorEvent

object TargetNode {
    def apply(id: Int, dID: Int, parent: ActorRef[Event]): Behavior[SensorEvent] = Behaviors.setup { context =>
        val tid = id
        val parentDroneID = dID
        val parentAddr = parent
        val estimator = context.spawn(KalmanFilterActor(), "estimator")
        var state = Matrix2x2(0, 0)
        var isInitialized = false
        val thresh = 2.5 // Threshold for target tracking distance validation
        
        // Activity tracking for cleanup
        var lastActivityTime = System.currentTimeMillis()
        val inactivityThreshold = 500L // 200 milliseconds
        var activityCheckCount = 0
        val maxInactivityChecks = 5 // Allow 3 consecutive inactivity checks before deletion
        
        context.log.info(s"Target $tid has been created and assigned to drone $parentDroneID")
        
        // Start activity monitoring timer
        Behaviors.withTimers { timers =>
            timers.startTimerWithFixedDelay("activity-check", CheckTargetActivity, 100.milliseconds) // Check every ~67ms (3 checks in 200ms)
            
            def initializeTarget(obsId: Int, data: Matrix2x2): Unit = {
                state = data
                isInitialized = true
                lastActivityTime = System.currentTimeMillis()
                activityCheckCount = 0 // Reset inactivity counter
                context.log.info(s"Target $tid initialized with position (${data.x}, ${data.y})")
                parentAddr ! TargetValid(dataID = obsId, targetID = tid)
                estimator ! Observe(data, dt=0.1, context.self)
            }

            def processSingleObservation(obsId: Int, data: Matrix2x2): Unit = {
                val dist = calculateDistance(state, data)
                lastActivityTime = System.currentTimeMillis()
                activityCheckCount = 0 // Reset inactivity counter
                
                if (dist < thresh) {
                    context.log.debug(s"Target $tid - Processing observation, distance: $dist")
                    parentAddr ! TargetValid(dataID = obsId, targetID = tid)
                    estimator ! Observe(data, dt=0.1, context.self)
                } else {
                    parentAddr ! TargetThresholdError(
                        dataID = obsId,
                        targetID = tid,
                        distance = dist,
                        expected = state,
                        observed = data
                    )
                }
            }

            def findClosestObservation(observations: List[(Int, Matrix2x2)], sender: ActorRef[SensorEvent]): Unit = {
                if (observations.isEmpty) {
                    context.log.warn(s"Target $tid received empty observation list")
                    return
                }

                lastActivityTime = System.currentTimeMillis()
                activityCheckCount = 0 // Reset inactivity counter

                // Calculate distances for all observations
                val observationsWithDistance = observations.map { case (obsId, obs) =>
                    val distance = calculateDistance(state, obs)
                    (obsId, obs, distance)
                }

                // Find the closest observation
                val (closestObsId, closestObs, closestDistance) = observationsWithDistance.minBy(_._3)

                context.log.debug(s"Target $tid - Closest observation: ID $closestObsId, distance $closestDistance")

                // Check if closest observation is within threshold
                if (closestDistance < thresh) {
                    // Process the closest observation
                    parentAddr ! TargetValid(dataID = closestObsId, targetID = tid)
                    estimator ! Observe(closestObs, dt=0.1, context.self)
                    
                    // Return the result to sender
                    sender ! ClosestObservationResult(tid, closestObsId, closestDistance, closestObs)
                    
                    context.log.debug(s"Target $tid - Accepted observation $closestObsId at (${closestObs.x}, ${closestObs.y})")
                } else {
                    // Even closest observation is too far
                    parentAddr ! TargetThresholdError(
                        dataID = closestObsId,
                        targetID = tid,
                        distance = closestDistance,
                        expected = state,
                        observed = closestObs
                    )
                    
                    // Return result indicating no suitable observation found
                    sender ! ClosestObservationResult(tid, -1, closestDistance, state)
                    
                    context.log.debug(s"Target $tid - Rejected all observations, closest was $closestDistance > $thresh")
                }
            }

            def checkAndHandleInactivity(): Behavior[SensorEvent] = {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastActivity = currentTime - lastActivityTime
                
                if (timeSinceLastActivity > inactivityThreshold) {
                    activityCheckCount += 1
                    context.log.debug(s"Target $tid - Inactivity detected: ${timeSinceLastActivity}ms since last activity (check $activityCheckCount/$maxInactivityChecks)")
                    
                    if (activityCheckCount >= maxInactivityChecks) {
                        // Target has been inactive for too long - initiate cleanup
                        context.log.info(s"Target $tid - CLEANUP: Target inactive for ${timeSinceLastActivity}ms, removing target")
                        
                        // Notify parent drone of cleanup
                        parentAddr ! TargetCleanupNotification(tid, s"Inactive for ${timeSinceLastActivity}ms")
                        
                        // Stop timers and terminate
                        timers.cancelAll()
                        Behaviors.stopped
                    } else {
                        // Continue monitoring
                        Behaviors.same
                    }
                } else {
                    // Target is still active - reset counter
                    if (activityCheckCount > 0) {
                        context.log.debug(s"Target $tid - Activity resumed: ${timeSinceLastActivity}ms since last activity")
                        activityCheckCount = 0
                    }
                    Behaviors.same
                }
            }
            
            Behaviors.receiveMessage {
                case TargetDataObs(obsId, data, sender) =>
                    context.log.debug(s"Target $tid - D$parentDroneID received single measurement $obsId at (${data.x}, ${data.y})")
                    
                    if (!isInitialized) {
                        initializeTarget(obsId, data)
                    } else {
                        processSingleObservation(obsId, data)
                    }
                    
                    Behaviors.same

                // Handle batch of observations - find closest one
                case BatchObservations(observations, sender) =>
                    context.log.debug(s"Target $tid - D$parentDroneID received batch of ${observations.length} observations")
                    
                    if (!isInitialized) {
                        // Initialize with first observation in batch
                        val (firstObsId, firstObs) = observations.head
                        initializeTarget(firstObsId, firstObs)
                        
                        // Return result indicating we used the first observation
                        sender ! ClosestObservationResult(tid, firstObsId, 0.0, firstObs)
                    } else {
                        // Find closest observation to current state
                        findClosestObservation(observations, sender)
                    }
                    
                    Behaviors.same

                case KalmanEstimate(dataPos, dataVel) =>
                    // Update state with Kalman filter estimate
                    val previousState = state
                    state = dataPos
                    lastActivityTime = System.currentTimeMillis()
                    activityCheckCount = 0 // Reset inactivity counter
                    
                    context.log.info(s"Target $tid - D$parentDroneID state updated from (${previousState.x}, ${previousState.y}) to (${dataPos.x}, ${dataPos.y})")
                    context.log.info(s"Target $tid - D$parentDroneID velocity estimate: (${dataVel.x}, ${dataVel.y})")
                    
                    // Send acknowledgment to parent drone with updated position
                    parentAddr ! TargetAck(tid, dataPos.x, dataPos.y)
                    
                    Behaviors.same

                case CheckTargetActivity =>
                    checkAndHandleInactivity()

                case unexpected =>
                    context.log.warn(s"Target $tid received unexpected message: $unexpected")
                    Behaviors.same
            }
        }
    }
    
    def calculateDistance(pos1: Matrix2x2, pos2: Matrix2x2): Double = {
        math.sqrt(math.pow(pos1.x - pos2.x, 2) + math.pow(pos1.y - pos2.y, 2))
    }
}