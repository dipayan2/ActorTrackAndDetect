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


// Add new message types for centralized communication
case class SensorDataToHub(
    fromDroneId: Int,
    gridData: GridTargetData, 
    timestamp: Long,
    sender: ActorRef[SensorEvent]
) extends SensorEvent

case class ProcessedResultFromHub(
    targetDroneId: Int,
    processedData: GridTargetData,
    detectedTargets: List[TargetInfo],
    timestamp: Long
) extends SensorEvent

case class TargetInfo(targetId: Int, position: Matrix2x2, confidence: Double) extends SensorEvent

// End centralized message types--specific to hub communication

// case class GridTargetData(gridMap: Map[(Int, Int), List[Matrix2x2]]) extends SensorEvent

// New measurement message type using dictionary data
case class GridMeasurement(data: GridTargetData, sender: ActorRef[SensorEvent]) extends SensorEvent


case class TargetData(id: Int, ref:ActorRef[TargetD], x:Double, y:Double, rowX:Int, rowY:Int) extends SensorEvent
case class TargetThresholdError(dataID: Int, targetID: Int, distance: Double, expected: Matrix2x2, observed: Matrix2x2) extends SensorEvent
case class TargetValid(dataID: Int, targetID: Int) extends SensorEvent
case class SharedTargetInfo(targetRef: ActorRef[TargetD], sourceDroneID: Int, targetID: Int, position: Matrix2x2, gridCell: (Int, Int)) extends SensorEvent

//Centralized Drone Actor
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
        val maxX = math.min(1000, x_loc + (x_range / 2))
        val minY = math.max(0, y_loc - (y_range / 2))
        val maxY = math.min(1000, y_loc + (y_range / 2))
        
        context.log.info(s"Drone $myID initialized: Center($x_loc, $y_loc), Area[$minX to $maxX, $minY to $maxY], Leader: $leader")
        
        val neighbors = scala.collection.mutable.ListBuffer[ActorRef[GraphCreate]]()
        val targets = scala.collection.mutable.ListBuffer[TargetData]()
        val targetMap = scala.collection.mutable.Map[(Int, Int), ListBuffer[Int]]()

        val sharedTargets = scala.collection.mutable.ListBuffer[TargetData]()
        val sharedTargetMap = scala.collection.mutable.Map[(Int, Int), ListBuffer[Int]]()
        var nCount = 0
        var tgtCount = 0
        
        // Track which observations have been validated by targets
        val pendingObservations = scala.collection.mutable.Map[Int, Matrix2x2]()
        val validatedObservations = scala.collection.mutable.Set[Int]()
        
        var frameCount = 0
        var sensorEventCount = 0
        var totalObservationsProcessed = 0

        // Find central hub (Drone 0) from neighbors
        var centralHub: Option[ActorRef[Event]] = None

        def addNeighbour(neighbour: ActorRef[GraphCreate]): Unit = {
            neighbors += neighbour
            nCount = nCount + 1
            
            // If this is Drone 0 being added as neighbor, store it as central hub
            // Note: You'll need to cast it properly based on your actor system setup
        }

        def findCentralHub(): Unit = {
            // Find Drone 0 among neighbors - this is a simplified approach
            // In practice, you might need a more sophisticated discovery mechanism
            neighbors.foreach { neighbor =>
                // This is a conceptual representation - you'd need to implement proper hub identification
                // For now, we assume the first neighbor added is the hub for non-hub drones
                if (!leader && centralHub.isEmpty) {
                    // Store reference to central hub (you'll need proper casting here)
                    context.log.info(s"Drone $myID identified central hub")
                }
            }
        }

        // MODIFIED: All your existing helper functions remain the same
        def addTarget(tgt: TargetData): Unit = {
            targets += tgt    
            val updatedList = targetMap.getOrElseUpdate((tgt.rowX, tgt.rowY), ListBuffer[Int]())
            updatedList += tgtCount
            tgtCount = tgtCount + 1
        }

        def updateTarget(tid: Int, obsX: Double, obsY: Double): Unit = {
            if (tid < targets.length) {
                val currTgt = targets(tid)
                val oldKey = (currTgt.rowX, currTgt.rowY)
                val newKey = coordinateToGridIndex(obsX, obsY)
                
                targets.update(tid, currTgt.copy(x = obsX, y = obsY, rowX = newKey._1, rowY = newKey._2))
                
                for {
                    fromList <- targetMap.get(oldKey)
                } {
                    fromList -= tid
                    targetMap.getOrElseUpdate(newKey, ListBuffer[Int]()) += tid
                }
            } else {
                context.log.warn(s"Drone $myID: Target ID $tid is out of bounds")
            }
        }

        // CENTRAL HUB PROCESSING - Only for Drone 0
        def centralizedProcessing(fromDroneId: Int, gridData: GridTargetData, timestamp: Long): Unit = {
            if (!leader) {
                context.log.error(s"Non-leader drone $myID received centralized processing request!")
                return
            }

            context.log.info(s"[CENTRAL HUB] Processing data from Drone $fromDroneId")
            context.log.info(s"[CENTRAL HUB] Received ${gridData.gridMap.values.map(_.length).sum} total observations")
            
            // Process the grid data using existing logic but centrally
            frameCount += 1
            gridTargetBehaviourBatch(gridData.gridMap)
            
            // Create result to send back to originating drone
            val detectedTargets = targets.map(t => TargetInfo(t.id, Matrix2x2(t.x, t.y), 0.85)).toList
            
            // Send processed results back to the originating drone
            val originatingDrone = neighbors.find(_ => true) // You'll need proper drone identification here
            // originatingDrone.foreach(_ ! ProcessedResultFromHub(fromDroneId, gridData, detectedTargets, timestamp))
            
            context.log.info(s"[CENTRAL HUB] Processed ${detectedTargets.length} targets for Drone $fromDroneId")
        }

        // Your existing processing functions remain unchanged
        def gridTargetBehaviourBatch(gridMap: Map[(Int, Int), List[Matrix2x2]]): Unit = {
            val previousFrameTotal = pendingObservations.size
            val previousFrameValid = validatedObservations.size
            val previousFrameInvalid = previousFrameTotal - previousFrameValid
            
            if (frameCount > 1) {
                val validationRate = if (previousFrameTotal > 0) {
                    (previousFrameValid.toDouble / previousFrameTotal.toDouble) * 100.0
                } else -1.0
                
                context.log.info(s"[SANDIA] Frame ${frameCount-1}, Drone ${myID}: Total=${previousFrameTotal}, " +
                            s"ValidationRate=${validationRate.formatted("%.1f")}%, Active=${targets.length}")
            }
            
            val unvalidatedObservations = pendingObservations.filterNot { case (dataID, _) =>
                validatedObservations.contains(dataID)
            }
            
            unvalidatedObservations.foreach { case (dataID, obs) =>
                val (rowX, rowY) = coordinateToGridIndex(obs.x, obs.y)
                context.log.info(s"Drone $myID: Creating new target for unvalidated observation $dataID at (${obs.x}, ${obs.y})")
                createNewTarget(dataID, obs, obs.x, obs.y, rowX, rowY)
            }
            
            pendingObservations.clear()
            validatedObservations.clear()
            
            var observationID = 0
            val globalObservationMap = scala.collection.mutable.Map[Int, Matrix2x2]()
            
            gridMap.foreach { case ((rowX, rowY), targetList) =>
                targetList.foreach { matrix =>
                    globalObservationMap(observationID) = matrix
                    pendingObservations(observationID) = matrix
                    observationID += 1
                }
            }
            
            gridMap.foreach { case ((rowX, rowY), targetList) =>
                if (targetMap.contains((rowX, rowY))) {
                    val cellObservations = targetList.zipWithIndex.map { case (matrix, localIdx) =>
                        val globalIdx = observationID - gridMap.values.map(_.length).sum + 
                                    gridMap.take(gridMap.keys.toList.indexOf((rowX, rowY))).values.map(_.length).sum + localIdx
                        (globalIdx, matrix)
                    }
                    
                    context.log.debug(s"Drone $myID: Sending ${cellObservations.length} observations to ${targetMap((rowX, rowY)).length} targets in cell ($rowX, $rowY)")
                    
                    for (targIdx <- targetMap((rowX, rowY))) {
                        if (targIdx < targets.length) {
                            targets(targIdx).ref ! BatchObservations(cellObservations, context.self)
                        }
                    }
                } else {
                    if (targetList.nonEmpty) {
                        val firstObs = targetList.head
                        val (obsX, obsY) = (firstObs.x, firstObs.y)
                        
                        val firstObsId = observationID - gridMap.values.map(_.length).sum + 
                                    gridMap.take(gridMap.keys.toList.indexOf((rowX, rowY))).values.map(_.length).sum
                        
                        createNewTarget(firstObsId, firstObs, obsX, obsY, rowX, rowY)
                        pendingObservations.remove(firstObsId)
                        
                        context.log.info(s"Drone $myID: Created new target for observation $firstObsId at ($obsX, $obsY) in cell ($rowX, $rowY)")
                    }
                }
            }
            
            context.log.debug(s"Drone $myID: Processed ${observationID} total observations across ${gridMap.size} grid cells using batch processing")
        }

        def createNewTarget(dataID: Int, observation: Matrix2x2, obsX: Double, obsY: Double, rowX: Int, rowY: Int): Unit = {
            val tref = context.spawn(TargetNode(tgtCount, myID, context.self), s"target-node-$tgtCount")
            val tdata = TargetData(tgtCount, tref, obsX, obsY, rowX, rowY)
            tref ! TargetDataObs(dataID, observation, context.self)
            addTarget(tdata)
            context.log.info(s"Drone $myID: Created new target $tgtCount at ($obsX, $obsY) in cell ($rowX, $rowY) for observation $dataID")
        }

        def removeTarget(targetId: Int): Unit = {
            if (targetId < targets.length && targetId >= 0) {
                val targetToRemove = targets(targetId)
                val gridKey = (targetToRemove.rowX, targetToRemove.rowY)
                
                context.log.info(s"[CLEANUP] Drone $myID removing target $targetId from position (${targetToRemove.x}, ${targetToRemove.y}) in grid ($gridKey)")
                
                // Remove from target map
                targetMap.get(gridKey) match {
                    case Some(targetList) =>
                        targetList -= targetId
                        // If grid cell is now empty, remove the key entirely
                        if (targetList.isEmpty) {
                            targetMap.remove(gridKey)
                            context.log.debug(s"[CLEANUP] Drone $myID removed empty grid cell $gridKey")
                        }
                    case None =>
                        context.log.warn(s"[CLEANUP] Drone $myID - Target $targetId not found in target map")
                }
                
                // Mark target as inactive/removed (don't actually remove from list to maintain indices)
                // Instead, you could set the ref to null or use an Option type
                val removedTarget = targetToRemove.copy(ref = null.asInstanceOf[ActorRef[TargetD]])
                targets.update(targetId, removedTarget)
                
                // Clean up any pending observations for this target
                pendingObservations.filterInPlace { case (obsId, obs) =>
                    val (obsRowX, obsRowY) = coordinateToGridIndex(obs.x, obs.y)
                    (obsRowX, obsRowY) != gridKey
                }
                
                // context.log.info(s"[SANDIA] Drone-$myID TargetCleanup TargetId-$targetId ActiveTargets-${targets.count(_.ref != null)} GridCells-${targetMap.size}")
                
            } else {
                context.log.error(s"[CLEANUP] Drone $myID - Invalid target ID for cleanup: $targetId")
            }
        }
        def graphCreation(): Behavior[Event] = Behaviors.receiveMessage {
            case AddNeighbour(neighbour, nid) =>
                addNeighbour(neighbour)
                context.log.info(s"$nid node got added in my $myID list")
                Behaviors.same
            
            case GraphDone =>
                context.log.info(s"$myID -- All nodes added")
                findCentralHub() // Identify central hub after all neighbors are added
                startNode
        }

        def startNode: Behavior[Event] = Behaviors.setup { context =>
            context.log.info(s"$myID -- Node started (Leader: $leader)")

            val simulationDataPath = "/Users/dmukherjee/UIUC/SandiaTrack/ActorTrackAndDetect/detectandtrack/trajectory_data"
            
            val sensor = context.spawn(Sensor(myID, context.self, minX, maxX, minY, maxY, simulationDataPath), "sensor")
            sensor ! Start
            
            Behaviors.receiveMessage {
                // MODIFIED: GridMeasurement handling - KEY CHANGE HERE
                case GridMeasurement(gridData, sender) =>
                    if (leader) {
                        // If this is the central hub (Drone 0), process locally as before
                        frameCount += 1
                        context.log.info(s"[CENTRAL HUB] Drone $myID processing frame $frameCount locally")
                        gridTargetBehaviourBatch(gridData.gridMap)
                    } else {
                        // If this is a worker drone, send data to central hub instead of processing locally
                        context.log.info(s"[WORKER] Drone $myID sending sensor data to Central Hub (frame data with ${gridData.gridMap.values.map(_.length).sum} observations)")
                        
                        // Find Drone 0 among neighbors and send data
                        // This is a simplified approach - you might need better hub identification
                        neighbors.headOption match {
                            case Some(hubNeighbor) =>
                                // Cast neighbor to proper type and send centralized message
                                // Note: This requires proper type handling in your actual implementation
                                hubNeighbor.asInstanceOf[ActorRef[Event]] ! SensorDataToHub(myID, gridData, System.currentTimeMillis(), sender)
                                context.log.debug(s"Worker Drone $myID sent data to hub")
                            case None =>
                                context.log.error(s"Worker Drone $myID has no neighbors to send data to!")
                        }
                    }
                    Behaviors.same

                // NEW: Handle centralized processing requests (only for Drone 0)
                case SensorDataToHub(fromDroneId, gridData, timestamp, sender) =>
                    if (leader) {
                        context.log.info(s"[CENTRAL HUB] Received sensor data from Worker Drone $fromDroneId")
                        centralizedProcessing(fromDroneId, gridData, timestamp)
                    } else {
                        context.log.error(s"Non-hub Drone $myID received SensorDataToHub message!")
                    }
                    Behaviors.same

                // NEW: Handle processed results from hub (only for worker drones)
                case ProcessedResultFromHub(targetDroneId, processedData, detectedTargets, timestamp) =>
                    val resultProcessingStartTime = System.nanoTime()
                    
                    if (targetDroneId == myID && !leader) {
                        context.log.info(s"[WORKER] Drone $myID received processed results from Central Hub: ${detectedTargets.length} targets detected")
                        
                        var highConfidenceTargets = 0
                        detectedTargets.foreach { target =>
                            if (target.confidence > 0.8) highConfidenceTargets += 1
                            context.log.info(s"Target ${target.targetId} detected at (${target.position.x}, ${target.position.y}) confidence: ${target.confidence}")
                        }
                        
                        val resultProcessingEndTime = System.nanoTime()
                        val resultProcessingTimeMs = (resultProcessingEndTime - resultProcessingStartTime) / 1000000.0
                        
                        context.log.info(s"[SANDIA] Drone-$myID ResultProcessing ProcessingTime-${resultProcessingTimeMs.formatted("%.2f")}ms DetectedTargets-${detectedTargets.length} HighConfidenceTargets-$highConfidenceTargets Role-Worker")
                        
                    } else if (targetDroneId == myID && leader) {
                        val resultProcessingEndTime = System.nanoTime()
                        val resultProcessingTimeMs = (resultProcessingEndTime - resultProcessingStartTime) / 1000000.0
                        context.log.error(s"[SANDIA] Drone-$myID ResultProcessing ProcessingTime-${resultProcessingTimeMs.formatted("%.2f")}ms DetectedTargets-0 HighConfidenceTargets-0 Role-CentralHub ERROR-ReceivedOwnResult")
                    }
                    Behaviors.same

                // All your existing message handlers remain unchanged
                case TargetAck(id, obsX, obsY) =>
                    context.log.info(s"Drone $myID received target ACK for target $id at ($obsX, $obsY)")
                    updateTarget(id, obsX, obsY)
                    Behaviors.same

                case TargetValid(dataID, targetID) =>
                    context.log.info(s"Drone $myID - Target $targetID successfully processed data $dataID (within threshold)")
                    validatedObservations += dataID
                    Behaviors.same
                
                case ClosestObservationResult(targetId, selectedObsId, distance, position) =>
                    if (selectedObsId >= 0) {
                        context.log.debug(s"Drone $myID - Target $targetId selected observation $selectedObsId (distance: $distance)")
                        validatedObservations += selectedObsId
                    } else {
                        context.log.debug(s"Drone $myID - Target $targetId rejected all observations (closest distance: $distance)")
                    }
                    Behaviors.same

                case TargetThresholdError(dataID, targetID, distance, expected, observed) =>
                    context.log.error(s"Drone $myID - Target $targetID threshold error for data $dataID:")
                    context.log.error(s"  Distance: $distance")
                    context.log.error(s"  Expected: (${expected.x}, ${expected.y})")
                    context.log.error(s"  Observed: (${observed.x}, ${observed.y})")
                    Behaviors.same

                case TargetCleanupNotification(targetId, reason) =>
                    context.log.info(s"[CLEANUP] Drone $myID - Target $targetId cleanup notification: $reason")
                    removeTarget(targetId)
                    Behaviors.same

                case SharedTargetInfo(targetRef, sourceDroneID, targetID, position, gridCell) =>
                    context.log.info(s"Drone $myID received shared target info: Target $targetID from Drone $sourceDroneID at (${position.x}, ${position.y})")
                    
                    val (obsX, obsY) = (position.x, position.y)
                    if (obsX >= minX && obsX <= maxX && obsY >= minY && obsY <= maxY) {
                        val (rowX, rowY) = coordinateToGridIndex(obsX, obsY)
                        val compositeTargetID = s"$sourceDroneID-$targetID"
                        
                        val alreadyExists = sharedTargets.exists(t => 
                            t.ref == targetRef && math.abs(t.x - obsX) < 0.1 && math.abs(t.y - obsY) < 0.1
                        )
                        
                        if (!alreadyExists) {
                            val sharedTargetData = TargetData(sharedTargets.length, targetRef, obsX, obsY, rowX, rowY)
                            sharedTargets += sharedTargetData
                            val existingSharedTargets = sharedTargetMap.getOrElseUpdate((rowX, rowY), ListBuffer[Int]())
                            existingSharedTargets += sharedTargets.length - 1
                            
                            context.log.info(s"Drone $myID added shared target $compositeTargetID from Drone $sourceDroneID to grid cell ($rowX, $rowY)")
                            context.log.info(s"Drone $myID now has ${targets.length} local targets and ${sharedTargets.length} shared targets")
                        } else {
                            context.log.debug(s"Drone $myID already tracking shared target from Drone $sourceDroneID")
                        }
                    }
                    Behaviors.same
            }
        }

        graphCreation()
    }

    def coordinateToGridIndex(x: Double, y: Double, cellWidth: Double = 2.0, cellHeight: Double = 2.0): (Int, Int) = {
        val column = (x / cellWidth).toInt
        val row = (y / cellHeight).toInt
        (row, column)
    }
}

    

// Decentralized Drone Actor

// object Drone {
//     def apply(id: Int, xpos: Int = 0, ypos: Int = 0, xrange: Int = 10, yrange: Int = 10, isLeader: Boolean = false): Behavior[Event] = Behaviors.setup { context =>
//         // Starting up the actor -- but wait till the graph is done
//         val myID = id
//         val leader = isLeader // Lets me know if I am the leader node
//         val x_loc = xpos  // Center X position of this drone's area
//         val y_loc = ypos  // Center Y position of this drone's area
//         val x_range = xrange  // X range this drone monitors
//         val y_range = yrange  // Y range this drone monitors
        
 
//         val minX = math.max(0, x_loc - (x_range / 2))
//         val maxX = math.min(1000, x_loc + (x_range / 2))
//         val minY = math.max(0, y_loc - (y_range / 2))
//         val maxY = math.min(1000, y_loc + (y_range / 2))
        
//         context.log.info(s"Drone $myID initialized: Center($x_loc, $y_loc), Area[$minX to $maxX, $minY to $maxY]")
        
//         val neighbors = scala.collection.mutable.ListBuffer[ActorRef[GraphCreate]]()
//         val targets = scala.collection.mutable.ListBuffer[TargetData]()
//         val targetMap = scala.collection.mutable.Map[(Int, Int), ListBuffer[Int]]() // Fixed type

//         val sharedTargets = scala.collection.mutable.ListBuffer[TargetData]()
//         val sharedTargetMap = scala.collection.mutable.Map[(Int, Int), ListBuffer[Int]]()
//         var nCount = 0
//         var tgtCount = 0
        
//         // Track which observations have been validated by targets
//         val pendingObservations = scala.collection.mutable.Map[Int, Matrix2x2]() // dataID -> observation
//         val validatedObservations = scala.collection.mutable.Set[Int]() // dataIDs that received TargetValid
        
//         var frameCount = 0

//         var sensorEventCount = 0
//         var totalObservationsProcessed = 0

//         def addNeighbour(neighbour: ActorRef[GraphCreate]): Unit = {
//             neighbors += neighbour // append the new element as the list
//             nCount = nCount + 1
//         }

//         def addTarget(tgt: TargetData): Unit = {
//             targets += tgt    
//             // Fixed: Use getOrElseUpdate to get ListBuffer, then add to it
//             val updatedList = targetMap.getOrElseUpdate((tgt.rowX, tgt.rowY), ListBuffer[Int]())
//             updatedList += tgtCount
//             tgtCount = tgtCount + 1
//         }

//         def updateTarget(tid: Int, obsX: Double, obsY: Double): Unit = {
//             if (tid < targets.length) { // Bounds check
//                 // Update the target data
//                 val currTgt = targets(tid) // Fixed: added val
//                 val oldKey = (currTgt.rowX, currTgt.rowY) // Fixed: added val
//                 val newKey = coordinateToGridIndex(obsX, obsY) // Fixed: added val
                
//                 targets.update(tid, currTgt.copy(x = obsX, y = obsY, rowX = newKey._1, rowY = newKey._2))
                
//                 // Update target map
//                 for {
//                     fromList <- targetMap.get(oldKey)
//                 } {
//                     fromList -= tid // remove from old position
//                     targetMap.getOrElseUpdate(newKey, ListBuffer[Int]()) += tid // add to new position
//                 }
//             } else {
//                 context.log.warn(s"Drone $myID: Target ID $tid is out of bounds")
//             }
//         }


//         // Check if target is in overlap zone and share with neighbors
//         def isInOverlapZone(obsX: Double, obsY: Double): Boolean = {
//             val overlapThreshold = 5.0 // 5-unit overlap zone
//             val nearLeftBoundary = obsX <= (minX + overlapThreshold)
//             val nearRightBoundary = obsX >= (maxX - overlapThreshold)
//             val nearBottomBoundary = obsY <= (minY + overlapThreshold)
//             val nearTopBoundary = obsY >= (maxY - overlapThreshold)
            
//             nearLeftBoundary || nearRightBoundary || nearBottomBoundary || nearTopBoundary
//         }
        
//     //    def shareTargetWithNeighbors(targetRef: ActorRef[TargetD], targetID: Int, position: Matrix2x2, gridCell: (Int, Int)): Unit = {
//     //         if (isInOverlapZone(position.x, position.y)) {
//     //             val sharedInfo = SharedTargetInfo(targetRef, myID, targetID, position, gridCell)
//     //             neighbors.foreach { neighbor =>
//     //                 neighbor ! sharedInfo
//     //             }
//     //             context.log.info(s"Drone $myID: Shared target $targetID at (${position.x}, ${position.y}) with ${neighbors.length} neighbors")
//     //         }
//     //     }


//         def targetBehaviour(data: MatrixList): Unit = {
//         // First, create new targets for any unvalidated pending observations from previous calls
//             val unvalidatedObservations = pendingObservations.filterNot { case (dataID, _) =>
//                 validatedObservations.contains(dataID)
//             }
            
//             unvalidatedObservations.foreach { case (dataID, obs) =>
//                 val (rowX, rowY) = coordinateToGridIndex(obs.x, obs.y)
//                 context.log.info(s"Drone $myID: Creating new target for unvalidated observation $dataID at (${obs.x}, ${obs.y})")
//                 createNewTarget(dataID, obs, obs.x, obs.y, rowX, rowY)
//             }
            
//             // Clean up - remove all processed observations
//             pendingObservations.clear()
//             validatedObservations.clear()
            
//             // Process the new observations
//             for (idx <- data.matrices.indices) {
//                 val (obsX, obsY) = (data.matrices(idx).x, data.matrices(idx).y)
//                 val (rowX, rowY) = coordinateToGridIndex(obsX, obsY)

//                 // Store observation as pending - waiting to see if any target validates it
//                 pendingObservations(idx) = data.matrices(idx)

//                 if (targetMap.contains((rowX, rowY))) {
//                     // Send observation to all targets in this grid cell
//                     for (targIdx <- targetMap((rowX, rowY))) {
//                         if (targIdx < targets.length) { // Bounds check
//                             targets(targIdx).ref ! TargetDataObs(idx, data.matrices(idx), context.self)
//                         }
//                     }
//                 } else {
//                     // No targets in this grid cell - immediately create new target
//                     createNewTarget(idx, data.matrices(idx), obsX, obsY, rowX, rowY)
//                     // Remove from pending since we just created a target for it
//                     pendingObservations.remove(idx)
//                 }
//             }
//         }

//         def gridTargetBehaviourBatch(gridMap: Map[(Int, Int), List[Matrix2x2]]): Unit = {

//             val previousFrameTotal = pendingObservations.size
//             val previousFrameValid = validatedObservations.size
//             val previousFrameInvalid = previousFrameTotal - previousFrameValid
            
//             // Log statistics for previous frame (if there was one)
//             if (frameCount > 1) { // Skip first frame since no previous data
//                 val validationRate = if (previousFrameTotal > 0) {
//                     (previousFrameValid.toDouble / previousFrameTotal.toDouble) * 100.0
//                 } else -1.0
                
//                 context.log.info(s"[SANDIA] Frame ${frameCount-1}, Drone ${myID}: Total=${previousFrameTotal}, " +
//                             s"VnvalidRate=${validationRate.formatted("%.1f")}%, Active=${targets.length}")
//             }
//             // Handle unvalidated observations from previous calls
//             val unvalidatedObservations = pendingObservations.filterNot { case (dataID, _) =>
//                 validatedObservations.contains(dataID)
//             }
            
//             unvalidatedObservations.foreach { case (dataID, obs) =>
//                 val (rowX, rowY) = coordinateToGridIndex(obs.x, obs.y)
//                 context.log.info(s"Drone $myID: Creating new target for unvalidated observation $dataID at (${obs.x}, ${obs.y})")
//                 createNewTarget(dataID, obs, obs.x, obs.y, rowX, rowY)
//             }
            
//             pendingObservations.clear()
//             validatedObservations.clear()
            
//             // Process grid-based observations with batch sending
//             var observationID = 0
//             val globalObservationMap = scala.collection.mutable.Map[Int, Matrix2x2]()
            
//             // First pass: Create global observation map
//             gridMap.foreach { case ((rowX, rowY), targetList) =>
//                 targetList.foreach { matrix =>
//                     globalObservationMap(observationID) = matrix
//                     pendingObservations(observationID) = matrix
//                     observationID += 1
//                 }
//             }
            
//             // Second pass: Send batches to existing targets in each grid cell
//             gridMap.foreach { case ((rowX, rowY), targetList) =>
//                 if (targetMap.contains((rowX, rowY))) {
//                     // Get all observations for this grid cell
//                     val cellObservations = targetList.zipWithIndex.map { case (matrix, localIdx) =>
//                         // Calculate global observation ID
//                         val globalIdx = observationID - gridMap.values.map(_.length).sum + 
//                                     gridMap.take(gridMap.keys.toList.indexOf((rowX, rowY))).values.map(_.length).sum + localIdx
//                         (globalIdx, matrix)
//                     }
                    
//                     context.log.debug(s"Drone $myID: Sending ${cellObservations.length} observations to ${targetMap((rowX, rowY)).length} targets in cell ($rowX, $rowY)")
                    
//                     // Send batch to all targets in this grid cell
//                     for (targIdx <- targetMap((rowX, rowY))) {
//                         if (targIdx < targets.length) {
//                             targets(targIdx).ref ! BatchObservations(cellObservations, context.self)
//                         }
//                     }
//                 } else {
//                     // No existing targets in this cell - create new target with first observation
//                     if (targetList.nonEmpty) {
//                         val firstObs = targetList.head
//                         val (obsX, obsY) = (firstObs.x, firstObs.y)
                        
//                         // Use a simple sequential ID for the first observation
//                         val firstObsId = observationID - gridMap.values.map(_.length).sum + 
//                                     gridMap.take(gridMap.keys.toList.indexOf((rowX, rowY))).values.map(_.length).sum
                        
//                         createNewTarget(firstObsId, firstObs, obsX, obsY, rowX, rowY)
//                         pendingObservations.remove(firstObsId)
                        
//                         context.log.info(s"Drone $myID: Created new target for observation $firstObsId at ($obsX, $obsY) in cell ($rowX, $rowY)")
//                     }
//                 }
//             }
            
//             context.log.debug(s"Drone $myID: Processed ${observationID} total observations across ${gridMap.size} grid cells using batch processing")
//         }

//         def removeTarget(targetId: Int): Unit = {
//             if (targetId < targets.length && targetId >= 0) {
//                 val targetToRemove = targets(targetId)
//                 val gridKey = (targetToRemove.rowX, targetToRemove.rowY)
                
//                 context.log.info(s"[CLEANUP] Drone $myID removing target $targetId from position (${targetToRemove.x}, ${targetToRemove.y}) in grid ($gridKey)")
                
//                 // Remove from target map
//                 targetMap.get(gridKey) match {
//                     case Some(targetList) =>
//                         targetList -= targetId
//                         // If grid cell is now empty, remove the key entirely
//                         if (targetList.isEmpty) {
//                             targetMap.remove(gridKey)
//                             context.log.debug(s"[CLEANUP] Drone $myID removed empty grid cell $gridKey")
//                         }
//                     case None =>
//                         context.log.warn(s"[CLEANUP] Drone $myID - Target $targetId not found in target map")
//                 }
                
//                 // Mark target as inactive/removed (don't actually remove from list to maintain indices)
//                 // Instead, you could set the ref to null or use an Option type
//                 val removedTarget = targetToRemove.copy(ref = null.asInstanceOf[ActorRef[TargetD]])
//                 targets.update(targetId, removedTarget)
                
//                 // Clean up any pending observations for this target
//                 pendingObservations.filterInPlace { case (obsId, obs) =>
//                     val (obsRowX, obsRowY) = coordinateToGridIndex(obs.x, obs.y)
//                     (obsRowX, obsRowY) != gridKey
//                 }
                
//                 // context.log.info(s"[SANDIA] Drone-$myID TargetCleanup TargetId-$targetId ActiveTargets-${targets.count(_.ref != null)} GridCells-${targetMap.size}")
                
//             } else {
//                 context.log.error(s"[CLEANUP] Drone $myID - Invalid target ID for cleanup: $targetId")
//             }
//         }
//         def gridTargetBehaviour(gridMap: Map[(Int, Int), List[Matrix2x2]]): Unit ={

//                 val unvalidatedObservations = pendingObservations.filterNot { case (dataID, _) =>
//                     validatedObservations.contains(dataID)
//                 }
                
//                 unvalidatedObservations.foreach { case (dataID, obs) =>
//                     val (rowX, rowY) = coordinateToGridIndex(obs.x, obs.y)
//                     context.log.info(s"Drone $myID: Creating new target for unvalidated observation $dataID at (${obs.x}, ${obs.y})")
//                     createNewTarget(dataID, obs, obs.x, obs.y, rowX, rowY)
//                 }
                
//                 // Clean up - remove all processed observations
//                 pendingObservations.clear()
//                 validatedObservations.clear()

//                 var observationID = 0
//                 gridMap.foreach { case ((rowX, rowY), targetList) =>
//                     context.log.info(s"Drone $myID: Processing grid cell ($rowX, $rowY) with ${targetList.length} targets")
                    
//                     targetList.foreach { matrix =>
//                         val (obsX, obsY) = (matrix.x, matrix.y)
                        
//                         // Store observation as pending - waiting to see if any target validates it
//                         pendingObservations(observationID) = matrix
                        
//                         if (targetMap.contains((rowX, rowY))) {
//                             // Send observation to all targets in this grid cell
//                             for (targIdx <- targetMap((rowX, rowY))) {
//                                 if (targIdx < targets.length) { // Bounds check
//                                     targets(targIdx).ref ! TargetDataObs(observationID, matrix, context.self)
//                                 }
//                             }
//                         } else {
//                             // No targets in this grid cell - immediately create new target
//                             createNewTarget(observationID, matrix, obsX, obsY, rowX, rowY)
//                             // Remove from pending since we just created a target for it
//                             pendingObservations.remove(observationID)
//                         }
                        
//                         observationID += 1
//                     }
//                 }

//                 context.log.info(s"Drone $myID: Processed ${observationID} total observations across ${gridMap.size} grid cells")

//         }


//         def createNewTarget(dataID: Int, observation: Matrix2x2, obsX: Double, obsY: Double, rowX: Int, rowY: Int): Unit = {
//             val tref = context.spawn(TargetNode(tgtCount, myID, context.self), s"target-node-$tgtCount")
//             val tdata = TargetData(tgtCount, tref, obsX, obsY, rowX, rowY)
//             tref ! TargetDataObs(dataID, observation, context.self)
//             addTarget(tdata)
//             context.log.info(s"Drone $myID: Created new target $tgtCount at ($obsX, $obsY) in cell ($rowX, $rowY) for observation $dataID")
//         }

//         def graphCreation(): Behavior[Event] = Behaviors.receiveMessage {
//             case AddNeighbour(neighbour, nid) =>
//                 addNeighbour(neighbour)
//                 context.log.info(s"$nid node got added in my $myID list")
//                 Behaviors.same
            
//             case GraphDone =>
//                 context.log.info(s"$myID -- All nodes added")
//                 startNode
//         }

//         /**
//          * The operation of the drone starts here
//          * 1. We start the sensor for this node, and start receiving information from the sensor
//          * 2. Assume the following algorithm -- that we can identify the expected object location, and then find an object there
//          */
//         def startNode: Behavior[Event] = Behaviors.setup { context =>
//             context.log.info(s"$myID -- Node started")

//             val simulationDataPath = "/Users/dmukherjee/UIUC/SandiaTrack/ActorTrackAndDetect/detectandtrack/trajectory_data" // Path to simulation data file
//             /**
//              * Starting the timed sensor node -- this will supply the information to the drone
//              * Pass the drone's monitoring area to the sensor
//              */
//             val sensor = context.spawn(Sensor(myID, context.self, minX, maxX, minY, maxY,simulationDataPath), "sensor")
//             sensor ! Start
            
//             Behaviors.receiveMessage {
//                 case Measurement(data, sender) =>
//                     /**
//                      * Create a process to get the location of the objects, which for now is the value it returns
//                      * Assume we have the updated states for each target
//                      */
//                     targetBehaviour(data)
//                     Behaviors.same 

//                 case GridMeasurement(gridData, sender) =>
//                     /**
//                      * Process dictionary-based sensor data where each key is (row, col) 
//                      * and each value is a list of targets at that grid position
//                      */
//                     // gridTargetBehaviour(gridData.gridMap)
//                     frameCount += 1
//                     context.log.debug(s"[SANDIA] Drone $myID processing frame $frameCount")
//                     gridTargetBehaviourBatch(gridData.gridMap)
//                     Behaviors.same

//                 case TargetAck(id, obsX, obsY) => // Fixed parameter names
//                     context.log.info(s"Drone $myID received target ACK for target $id at ($obsX, $obsY)")
//                     updateTarget(id, obsX, obsY)
//                     Behaviors.same

//                 case TargetValid(dataID, targetID) =>
//                     context.log.info(s"Drone $myID - Target $targetID successfully processed data $dataID (within threshold)")
//                     // Mark this observation as validated
//                     validatedObservations += dataID

//                     Behaviors.same
                
//                 case ClosestObservationResult(targetId, selectedObsId, distance, position) =>
//                     if (selectedObsId >= 0) {
//                         context.log.debug(s"Drone $myID - Target $targetId selected observation $selectedObsId (distance: $distance)")
//                         // Mark this observation as processed
//                         validatedObservations += selectedObsId
//                     } else {
//                         context.log.debug(s"Drone $myID - Target $targetId rejected all observations (closest distance: $distance)")
//                     }
//                     Behaviors.same

//                 case TargetThresholdError(dataID, targetID, distance, expected, observed) =>
//                     context.log.error(s"Drone $myID - Target $targetID threshold error for data $dataID:")
//                     context.log.error(s"  Distance: $distance")
//                     context.log.error(s"  Expected: (${expected.x}, ${expected.y})")
//                     context.log.error(s"  Observed: (${observed.x}, ${observed.y})")
//                     Behaviors.same

//                 case TargetCleanupNotification(targetId, reason) =>
//                     context.log.info(s"[CLEANUP] Drone $myID - Target $targetId cleanup notification: $reason")
//                     removeTarget(targetId)
//                     Behaviors.same

//                 case SharedTargetInfo(targetRef, sourceDroneID, targetID, position, gridCell) =>
//                     context.log.info(s"Drone $myID received shared target info: Target $targetID from Drone $sourceDroneID at (${position.x}, ${position.y})")
                    
//                     // Store reference to shared target if it's in our monitoring area
//                     val (obsX, obsY) = (position.x, position.y)
//                     if (obsX >= minX && obsX <= maxX && obsY >= minY && obsY <= maxY) {
//                         val (rowX, rowY) = coordinateToGridIndex(obsX, obsY)
                        
//                         // Create a composite target ID to avoid conflicts: droneID-targetID
//                         val compositeTargetID = s"$sourceDroneID-$targetID"
                        
//                         // Check if we already have this shared target (avoid duplicates)
//                         val alreadyExists = sharedTargets.exists(t => 
//                             t.ref == targetRef && math.abs(t.x - obsX) < 0.1 && math.abs(t.y - obsY) < 0.1
//                         )
                        
//                         if (!alreadyExists) {
//                             // Create a shared target entry with composite ID as the target ID
//                             val sharedTargetData = TargetData(sharedTargets.length, targetRef, obsX, obsY, rowX, rowY)
                            
//                             // Add to shared targets list and map
//                             sharedTargets += sharedTargetData
//                             val existingSharedTargets = sharedTargetMap.getOrElseUpdate((rowX, rowY), ListBuffer[Int]())
//                             existingSharedTargets += sharedTargets.length - 1
                            
//                             context.log.info(s"Drone $myID added shared target $compositeTargetID from Drone $sourceDroneID to grid cell ($rowX, $rowY)")
//                             context.log.info(s"Drone $myID now has ${targets.length} local targets and ${sharedTargets.length} shared targets")
//                         } else {
//                             context.log.debug(s"Drone $myID already tracking shared target from Drone $sourceDroneID")
//                         }
//                     }
                    
//                     Behaviors.same

//             }
//         }

//         // We should add a logic for sensor crashing and then sending the trigger to restart
//         graphCreation()
//     }

//     def coordinateToGridIndex(x: Double, y: Double, cellWidth: Double = 2.0, cellHeight: Double = 2.0): (Int, Int) = {
//         val column = (x / cellWidth).toInt
//         val row = (y / cellHeight).toInt
//         (row, column)
//     }
// }