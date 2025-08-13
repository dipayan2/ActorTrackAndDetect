package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import scala.io.Source._

trait TargetD extends SensorEvent
case class TargetAck(id:Int, ObsX:Double, ObsY: Double) extends TargetD
case class TargetDataObs(id: Int, data: Matrix2x2, sender: ActorRef[SensorEvent]) extends TargetD
case class KalmanEstimate(dataPos: Matrix2x2, dataVel: Matrix2x2) extends TargetD
// Measurement(data: Double, sender: ActorRef[SensorEvent])
// case object 

object TargetNode {
    // A target will be created based on the measurements of the sensor from the drone
    def apply(id: Int, dID: Int, parent: ActorRef[Event]): Behavior[SensorEvent] = Behaviors.setup { context =>
        val tid = id
        val parentDroneID = dID
        val parentAddr = parent
        val estimator = context.spawn(KalmanFilterActor(), "estimator")
        var state = Matrix2x2(0, 0)
        var isInitialized = false // Fixed typo: isinitilized -> isInitialized
        val thresh = 2.0 // Threshold for target tracking distance validation

        context.log.info(s"Target $tid has been created and assigned to drone $parentDroneID")

        Behaviors.receiveMessage {
            case TargetDataObs(obsId, data, sender) => // Added obsId parameter name
                context.log.info(s"Target $tid - D$parentDroneID received measurement $obsId at (${data.x}, ${data.y})")
                
                if (!isInitialized) {
                    // Initialize target state with first observation
                    state = data
                    isInitialized = true
                    context.log.info(s"Target $tid initialized with position (${data.x}, ${data.y})")
                    parentAddr ! TargetValid(dataID = obsId, targetID = tid)

                    // Send first observation to Kalman filter
                    estimator ! Observe(data, 1.0, context.self)
                } else {
                    // Check if observation is close enough to current state
                    val dist = calculateDistance(state, data)
                    
                    if (dist < thresh) {
                        // Observation is within acceptable range - process it
                        context.log.debug(s"Target $tid - Processing observation, distance: $dist")
                        parentAddr ! TargetValid(dataID = obsId, targetID = tid)

                        estimator ! Observe(data, 1.0, context.self)
                    } else {
                        // Observation is too far from expected state
                        // context.log.warn(s"Target $tid - D$parentDroneID received measurement too far from current state. Distance: $dist > $thresh. Current: (${state.x}, ${state.y}), Observed: (${data.x}, ${data.y})")
                        
                        // Send error message to parent drone with dataID and targetID
                        parentAddr ! TargetThresholdError(
                            dataID = obsId,
                            targetID = tid,
                            distance = dist,
                            expected = state,
                            observed = data
                        )
                        
                        // DO NOT process the observation - it's outside the threshold
                        // The estimator will not receive this outlier observation
                    }
                }
                
                Behaviors.same

            case KalmanEstimate(dataPos, dataVel) =>
                // Update state with Kalman filter estimate
                val previousState = state
                state = dataPos
                
                context.log.info(s"Target $tid - D$parentDroneID state updated from (${previousState.x}, ${previousState.y}) to (${dataPos.x}, ${dataPos.y})")
                context.log.info(s"Target $tid - D$parentDroneID velocity estimate: (${dataVel.x}, ${dataVel.y})")
                
                // Send acknowledgment to parent drone with updated position
                parentAddr ! TargetAck(tid, dataPos.x, dataPos.y)
                
                Behaviors.same
                
            case unexpected =>
                context.log.warn(s"Target $tid received unexpected message: $unexpected")
                Behaviors.same
        }
    }
    
    def calculateDistance(pos1: Matrix2x2, pos2: Matrix2x2): Double = {
        math.sqrt(math.pow(pos1.x - pos2.x, 2) + math.pow(pos1.y - pos2.y, 2))
    }
}