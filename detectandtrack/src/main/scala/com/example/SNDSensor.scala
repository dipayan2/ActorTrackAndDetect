package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

import scala.util.Random
import scala.io.Source._

import java.nio.file.{Files, Paths}
import scala.util.{Try, Success, Failure}
/**
 *  Sensor Node: 
 *  1. Read from global simulation data files at timed intervals
 *  2. Extract targets within this sensor's monitoring area (minX to maxX, minY to maxY)
 *  3. Send filtered data as dictionary format to parent drone
 *  4. Advance simulation time step with each reading
*/



case class SimulationTarget(x: Double, y: Double, intensity: Double, targetId: Int)
case class SimulationFrame(frameNumber: Int, targets: List[SimulationTarget])


// case object Start extends SensorEvent
case object Restart extends SensorEvent
// case object SendData extends SensorEvent
case object Stop extends SensorEvent

case class GridTargetData(gridMap: Map[(Int, Int), List[Matrix2x2]]) extends SensorEvent

object Sensor {

    // Constants
    val constantVoltage = 10.0
    val measurementNoise = 1.0
    val processNoise = 1e-5


    def apply(id: Int, drone: ActorRef[SensorEvent], minX: Int, maxX: Int, minY: Int, maxY: Int,
      simulationDataPath: String = "simulation_data"): Behavior[SensorEvent] = Behaviors.setup { context =>
        context.log.info(s"Starting Sensor for Drone $id - Monitoring area [$minX to $maxX, $minY to $maxY]")
        // var msgCounter = 0
        val parentDrone = drone
        val sid = id
        
   
        val monitorMinX = minX
        val monitorMaxX = maxX
        val monitorMinY = minY
        val monitorMaxY = maxY
        val simDataPath = simulationDataPath

               // Simulation state
        var currentFrame = 0
        var totalFrames = 100 // Based on your notebook: T = 200
        var trajectoryData: Option[Map[Int, List[(Double, Double, Int)]]] = None
        // Load simulation data on startup
        loadSimulationData(simDataPath) match {
            case Success(data) =>
                trajectoryData = Some(data)
                totalFrames = data.keys.max + 1
                context.log.info(s" Sensor $sid loaded trajectory data: ${totalFrames} frames")
            case Failure(exception) =>
                context.log.error(s" Sensor $sid failed to load trajectory data: ${exception.getMessage}")
                context.log.info(s" Sensor $sid falling back to random generation")
        }



        Behaviors.withTimers { timers =>

            def running: Behavior[SensorEvent] = Behaviors.receiveMessage {
                case Start =>
                    Behaviors.same 
                
                case Restart =>
                    context.log.info(s"Sensor $sid restarting")
                    timers.cancel("data-sender")
                    timers.startTimerWithFixedDelay("data-sender", SendData, 100.milliseconds)
                    Behaviors.same   

                case Stop =>
                    timers.cancel("data-sender")
                    idle
                
                case SendData =>
                    // Generate observations within this drone's specific area
                    // Get current frame data from simulation or generate fallback
                    // Get current frame data from trajectory or generate fallback
                    val gridData = trajectoryData match {
                        case Some(trajectories) if trajectories.contains(currentFrame) =>
                            // Extract targets from trajectory data for current frame
                            extractTargetsFromTrajectory(trajectories, currentFrame, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY)
                        case Some(trajectories) =>
                            // Frame not found, loop back to start
                            currentFrame = (currentFrame+1) % totalFrames
                            if (trajectories.contains(currentFrame)) {
                                extractTargetsFromTrajectory(trajectories, currentFrame, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY)
                            } else {
                                context.log.warn(s" Sensor $sid no data for frame $currentFrame, using random fallback")
                                generateRandomGridData(50, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY) // Reduced from 1000
                            }
                        case None =>
                            // Fallback to random generation
                            generateRandomGridData(50, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY) // Reduced from 1000
                    }
                    
                    // Send data to drone
                    parentDrone ! GridMeasurement(gridData, context.self)
                    
                    // Advance simulation frame
                    currentFrame = (currentFrame + 1) % totalFrames
                    
                    if (currentFrame % 50 == 0) { // Log every 50th frame to avoid spam
                        context.log.debug(s"Sensor $sid sent frame $currentFrame data with ${gridData.gridMap.values.map(_.length).sum} total targets")
                    }
                    
                    Behaviors.same 

            }

            def idle: Behavior[SensorEvent] = Behaviors.receiveMessage {
                case Start =>
                    timers.startTimerWithFixedDelay("data-sender", SendData, 100.milliseconds)
                    running
            }
            
            /*
            * This is the initial state of the sensor
            */
            idle 
        }
    }



  // Helper function to load simulation data from files
    def loadSimulationData(dataPath: String): Try[Map[Int, List[(Double, Double, Int)]]] = Try {
        import java.io.File
        import scala.io.Source
        
        // context.log.info(s"Loading trajectory data from: $dataPath")
        
        val dir = new File(dataPath)
        if (!dir.exists()) {
            throw new Exception(s"Directory does not exist: $dataPath")
        }
        
        // Find all frame files
        val frameFiles = dir.listFiles()
            .filter(_.getName.matches("frame_\\d+\\.txt"))
            .sortBy(f => f.getName.replace("frame_", "").replace(".txt", "").toInt)
        
        // context.log.info(s"Found ${frameFiles.length} trajectory files")
        
        if (frameFiles.isEmpty) {
            throw new Exception(s"No frame trajectory files found in $dataPath")
        }
        
        val trajectoryMap = scala.collection.mutable.Map[Int, List[(Double, Double, Int)]]()
        
        frameFiles.foreach { file =>
            val frameNumber = file.getName.replace("frame_", "").replace(".txt", "").toInt
            val source = Source.fromFile(file)
            try {
                val lines = source.getLines().toList
                val targets = lines.drop(1).map { line => // Skip header
                    val parts = line.split(",")
                    if (parts.length == 3) {
                        (parts(0).trim.toDouble, parts(1).trim.toDouble, parts(2).trim.toInt)
                    } else {
                        throw new Exception(s"Invalid line format in ${file.getName}: $line")
                    }
                }
                trajectoryMap(frameNumber) = targets
            } finally {
                source.close()
            }
        }
        
        // context.log.info(s"[SANDIA] Successfully loaded trajectories for ${trajectoryMap.size} frames")
        trajectoryMap.toMap
    }

    // Extract targets from trajectory data for current frame within sensor's area
    def extractTargetsFromTrajectory(trajectoryData: Map[Int, List[(Double, Double, Int)]], 
                                   frameIndex: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): GridTargetData = {
        
        val frameTargets = trajectoryData.getOrElse(frameIndex, List.empty)
        
        // Filter targets within this sensor's monitoring area
        val targetsInArea = frameTargets.filter { case (x, y, targetId) =>
            x >= minX && x <= maxX && y >= minY && y <= maxY
        }
        
        // Convert to Matrix2x2 format
        val matrices = targetsInArea.map { case (x, y, _) =>
            Matrix2x2(x, y)
        }
        
        // Group by grid coordinates
        val groupedByGrid = matrices.groupBy { matrix =>
            coordinateToGridIndex(matrix.x, matrix.y)
        }
        
        GridTargetData(groupedByGrid)
    }
    // Fallback random generation (same as before)
    def generateRandomGridData(n: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): GridTargetData = {
        val matrices = List.fill(n)(genMatrixForArea(minX, maxX, minY, maxY))
        val groupedByGrid = matrices.groupBy { matrix =>
            coordinateToGridIndex(matrix.x, matrix.y)
        }
        GridTargetData(groupedByGrid)
    }

    def genMatrixForArea(minX: Int, maxX: Int, minY: Int, maxY: Int): Matrix2x2 = {
        Matrix2x2(
            Random.between(minX.toDouble, maxX.toDouble),
            Random.between(minY.toDouble, maxY.toDouble)
        )  
    }

    // Helper function to convert coordinates to grid indices
    def coordinateToGridIndex(x: Double, y: Double, cellWidth: Double = 2.0, cellHeight: Double = 2.0): (Int, Int) = {
        val column = (x / cellWidth).toInt
        val row = (y / cellHeight).toInt
        (row, column)
    }

    // New function to generate data as a dictionary grouped by grid coordinates
    def generateGridTargetDataForArea(n: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): GridTargetData = {
        // Generate n random matrices
        val matrices = List.fill(n)(genMatrixForArea(minX, maxX, minY, maxY))
        
        // Group matrices by their grid coordinates
        val groupedByGrid = matrices.groupBy { matrix =>
            coordinateToGridIndex(matrix.x, matrix.y)
        }
        
        GridTargetData(groupedByGrid)
    }


    def generateMatrixListForArea(n: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): MatrixList = {
        MatrixList(List.fill(n)(genMatrixForArea(minX, maxX, minY, maxY)))
    }


}