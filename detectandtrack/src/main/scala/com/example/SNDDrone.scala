package com.example
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
// import akka.actor.typed.receptionist.Receptionist
// import org.apache.commons.math3.linear.ArrayRealVector

import scala.concurrent.duration._
import akka.actor.Actor
import java.lang.annotation.Target
import javax.sound.sampled.TargetDataLine
import scala.collection.mutable.ListBuffer


trait SensorEvent extends Event
case object Start extends SensorEvent
case class Estimate(data: Double,old: Double, sender: ActorRef[SensorEvent]) extends SensorEvent
case object SendData extends SensorEvent
case object NextData extends SensorEvent
case class Matrix2x2(x: Double, y: Double) extends SensorEvent
case class MatrixList(matrices: List[Matrix2x2]) extends SensorEvent
case class Measurement(data: MatrixList, sender: ActorRef[SensorEvent]) extends SensorEvent
case class TargetData(id: Int, ref:ActorRef[TargetD], x:Double, y:Double, rowX:Int, rowY:Int) extends SensorEvent
case class TargetThresholdError(dataID: Int, targetID: Int, distance: Double, expected: Matrix2x2, observed: Matrix2x2) extends SensorEvent
case class TargetValid(dataID: Int, targetID: Int) extends SensorEvent


object Drone {
    def apply(id: Int, xpos: Int = 0, ypos: Int = 0, xrange: Int = 10, yrange: Int = 10, isLeader: Boolean = false): Behavior[Event] = Behaviors.setup { context =>
        // Starting up the actor -- but wait till the graph is done
        val myID = id
        val leader = isLeader // Lets me know if I am the leader node
        val x_loc = xpos  // Center X position of this drone's area
        val y_loc = ypos  // Center Y position of this drone's area
        val x_range = xrange  // X range this drone monitors
        val y_range = yrange  // Y range this drone monitors
        
 
        val minX = math.max(0, x_loc - (x_range / 2))
        val maxX = math.min(100, x_loc + (x_range / 2))
        val minY = math.max(0, y_loc - (y_range / 2))
        val maxY = math.min(100, y_loc + (y_range / 2))
        
        context.log.info(s"Drone $myID initialized: Center($x_loc, $y_loc), Area[$minX to $maxX, $minY to $maxY]")
        
        val neighbors = scala.collection.mutable.ListBuffer[ActorRef[GraphCreate]]()
        val targets = scala.collection.mutable.ListBuffer[TargetData]()
        val targetMap = scala.collection.mutable.Map[(Int, Int), ListBuffer[Int]]() // Fixed type
        var nCount = 0
        var tgtCount = 0
        
        // Track which observations have been validated by targets
        val pendingObservations = scala.collection.mutable.Map[Int, Matrix2x2]() // dataID -> observation
        val validatedObservations = scala.collection.mutable.Set[Int]() // dataIDs that received TargetValid
        
        
        var sensorEventCount = 0
        var totalObservationsProcessed = 0

        def addNeighbour(neighbour: ActorRef[GraphCreate]): Unit = {
            neighbors += neighbour // append the new element as the list
            nCount = nCount + 1
        }

        def addTarget(tgt: TargetData): Unit = {
            targets += tgt    
            // Fixed: Use getOrElseUpdate to get ListBuffer, then add to it
            val updatedList = targetMap.getOrElseUpdate((tgt.rowX, tgt.rowY), ListBuffer[Int]())
            updatedList += tgtCount
            tgtCount = tgtCount + 1
        }

        def updateTarget(tid: Int, obsX: Double, obsY: Double): Unit = {
            if (tid < targets.length) { // Bounds check
                // Update the target data
                val currTgt = targets(tid) // Fixed: added val
                val oldKey = (currTgt.rowX, currTgt.rowY) // Fixed: added val
                val newKey = coordinateToGridIndex(obsX, obsY) // Fixed: added val
                
                targets.update(tid, currTgt.copy(x = obsX, y = obsY, rowX = newKey._1, rowY = newKey._2))
                
                // Update target map
                for {
                    fromList <- targetMap.get(oldKey)
                } {
                    fromList -= tid // remove from old position
                    targetMap.getOrElseUpdate(newKey, ListBuffer[Int]()) += tid // add to new position
                }
            } else {
                context.log.warn(s"Drone $myID: Target ID $tid is out of bounds")
            }
        }

        def targetBehaviour(data: MatrixList): Unit = {
        // First, create new targets for any unvalidated pending observations from previous calls
            val unvalidatedObservations = pendingObservations.filterNot { case (dataID, _) =>
                validatedObservations.contains(dataID)
            }
            
            unvalidatedObservations.foreach { case (dataID, obs) =>
                val (rowX, rowY) = coordinateToGridIndex(obs.x, obs.y)
                context.log.info(s"Drone $myID: Creating new target for unvalidated observation $dataID at (${obs.x}, ${obs.y})")
                createNewTarget(dataID, obs, obs.x, obs.y, rowX, rowY)
            }
            
            // Clean up - remove all processed observations
            pendingObservations.clear()
            validatedObservations.clear()
            
            // Process the new observations
            for (idx <- data.matrices.indices) {
                val (obsX, obsY) = (data.matrices(idx).x, data.matrices(idx).y)
                val (rowX, rowY) = coordinateToGridIndex(obsX, obsY)

                // Store observation as pending - waiting to see if any target validates it
                pendingObservations(idx) = data.matrices(idx)

                if (targetMap.contains((rowX, rowY))) {
                    // Send observation to all targets in this grid cell
                    for (targIdx <- targetMap((rowX, rowY))) {
                        if (targIdx < targets.length) { // Bounds check
                            targets(targIdx).ref ! TargetDataObs(idx, data.matrices(idx), context.self)
                        }
                    }
                } else {
                    // No targets in this grid cell - immediately create new target
                    createNewTarget(idx, data.matrices(idx), obsX, obsY, rowX, rowY)
                    // Remove from pending since we just created a target for it
                    pendingObservations.remove(idx)
                }
            }
        }

        def createNewTarget(dataID: Int, observation: Matrix2x2, obsX: Double, obsY: Double, rowX: Int, rowY: Int): Unit = {
            val tref = context.spawn(TargetNode(tgtCount, myID, context.self), s"target-node-$tgtCount")
            val tdata = TargetData(tgtCount, tref, obsX, obsY, rowX, rowY)
            tref ! TargetDataObs(dataID, observation, context.self)
            addTarget(tdata)
            context.log.info(s"Drone $myID: Created new target $tgtCount at ($obsX, $obsY) in cell ($rowX, $rowY) for observation $dataID")
        }

        def graphCreation(): Behavior[Event] = Behaviors.receiveMessage {
            case AddNeighbour(neighbour, nid) =>
                addNeighbour(neighbour)
                context.log.info(s"$nid node got added in my $myID list")
                Behaviors.same
            
            case GraphDone =>
                context.log.info(s"$myID -- All nodes added")
                startNode
        }

        /**
         * The operation of the drone starts here
         * 1. We start the sensor for this node, and start receiving information from the sensor
         * 2. Assume the following algorithm -- that we can identify the expected object location, and then find an object there
         */
        def startNode: Behavior[Event] = Behaviors.setup { context =>
            context.log.info(s"$myID -- Node started")
            /**
             * Starting the timed sensor node -- this will supply the information to the drone
             * Pass the drone's monitoring area to the sensor
             */
            val sensor = context.spawn(Sensor(myID, context.self, minX, maxX, minY, maxY), "sensor")
            sensor ! Start
            
            Behaviors.receiveMessage {
                case Measurement(data, sender) =>
                    /**
                     * Create a process to get the location of the objects, which for now is the value it returns
                     * Assume we have the updated states for each target
                     */
                    targetBehaviour(data)
                    Behaviors.same 

                case TargetAck(id, obsX, obsY) => // Fixed parameter names
                    context.log.info(s"Drone $myID received target ACK for target $id at ($obsX, $obsY)")
                    updateTarget(id, obsX, obsY)
                    Behaviors.same

                case TargetValid(dataID, targetID) =>
                    context.log.info(s"Drone $myID - Target $targetID successfully processed data $dataID (within threshold)")
                    // Mark this observation as validated
                    validatedObservations += dataID
                    // Could track statistics here:
                    // - Count of valid observations per target
                    // - Success rate tracking
                    // - Target health monitoring
                    Behaviors.same

                case TargetThresholdError(dataID, targetID, distance, expected, observed) =>
                    context.log.error(s"Drone $myID - Target $targetID threshold error for data $dataID:")
                    context.log.error(s"  Distance: $distance")
                    context.log.error(s"  Expected: (${expected.x}, ${expected.y})")
                    context.log.error(s"  Observed: (${observed.x}, ${observed.y})")
                    
                    // Handle the error - options include:
                    // 1. Reset the target
                    // 2. Adjust sensor calibration
                    // 3. Mark target as potentially lost
                    // 4. Increase uncertainty in tracking
                    
                    Behaviors.same
            }
        }

        // We should add a logic for sensor crashing and then sending the trigger to restart
        graphCreation()
    }

    def coordinateToGridIndex(x: Double, y: Double, cellWidth: Double = 2.0, cellHeight: Double = 2.0): (Int, Int) = {
        val column = (x / cellWidth).toInt
        val row = (y / cellHeight).toInt
        (row, column)
    }
}