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
        var totalFrames = 200 // Based on your notebook: T = 200
        var simulationData: Option[Array[Array[Array[Double]]]] = None // [T][N][N] format
        // Load simulation data on startup
        loadSimulationData(simDataPath) match {
            case Success(data) =>
                simulationData = Some(data)
                totalFrames = data.length
                context.log.info(s"Sensor $sid loaded simulation data: ${totalFrames} frames")
            case Failure(exception) =>
                context.log.error(s"Sensor $sid failed to load simulation data: ${exception.getMessage}")
                context.log.info(s"Sensor $sid falling back to random generation")
        }


        Behaviors.withTimers { timers =>

            def running: Behavior[SensorEvent] = Behaviors.receiveMessage {
                case Start =>
                    Behaviors.same 
                
                case Restart =>
                    context.log.info(s"Sensor $sid restarting")
                    timers.cancel("data-sender")
                    timers.startTimerWithFixedDelay("data-sender", SendData, 40.milliseconds)
                    Behaviors.same   

                case Stop =>
                    timers.cancel("data-sender")
                    idle
                
                case SendData =>
                    // Generate observations within this drone's specific area
                    // Get current frame data from simulation or generate fallback
                    val gridData = simulationData match {
                        case Some(simData) if currentFrame < totalFrames =>
                            // Extract targets from simulation for current frame
                            extractTargetsFromSimulation(simData, currentFrame, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY)
                        case Some(simData) =>
                            // Simulation finished, loop back to start
                            currentFrame = 0
                            extractTargetsFromSimulation(simData, currentFrame, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY)
                        case None =>
                            // Fallback to random generation
                            context.log.debug(s"Sensor $sid using random fallback data")
                            generateGridTargetDataForArea(1000, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY)
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
        def loadSimulationData(dataPath: String): Try[Array[Array[Array[Double]]]] = Try {
            // Try to load from numpy-style format files or CSV
            // This assumes you've saved your simulation arrays W, X, Y, etc. to files
            
            // For now, implement a simple CSV-based loader
            // You would save each frame as "frame_<t>.csv" from your Python simulation
            val frameFiles = (0 until 200).map(t => s"$dataPath/frame_$t.csv")
            
            val frames = frameFiles.map { filename =>
                val lines = Files.readAllLines(Paths.get(filename)).toArray
                lines.map(line => 
                    line.toString.split(",").map(_.trim.toDouble)
                ).toArray
            }.toArray
            
            frames
        }

        // Extract targets from simulation data for current frame within sensor's area
        def extractTargetsFromSimulation(simulationData: Array[Array[Array[Double]]], 
                                    frameIndex: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): GridTargetData = {
            
            val frameData = simulationData(frameIndex)
            val N = frameData.length // Should be 200 from your simulation
            
            val targets = scala.collection.mutable.ListBuffer[Matrix2x2]()
            
            // Scan the simulation frame for non-zero (target) pixels within our sensor area
            for (row <- 0 until N; col <- 0 until N) {
                val intensity = frameData(row)(col)
                
                // Convert grid indices back to world coordinates (assuming 1:1 mapping for simplicity)
                val worldX = col.toDouble
                val worldY = row.toDouble
                
                // Check if this target is within our sensor's monitoring area and has significant intensity
                if (worldX >= minX && worldX <= maxX && 
                    worldY >= minY && worldY <= maxY && 
                    math.abs(intensity) > 0.05) { // Threshold for detecting targets
                    
                    targets += Matrix2x2(worldX, worldY)
                }
            }
            
            // Group by grid coordinates and return as dictionary format
            val groupedByGrid = targets.toList.groupBy { matrix =>
                coordinateToGridIndex(matrix.x, matrix.y)
            }
            
            GridTargetData(groupedByGrid.toMap)
        }


    def genMatrixForArea(minX: Int, maxX: Int, minY: Int, maxY: Int): Matrix2x2 = {
        Matrix2x2(
            Random.between(minX.toDouble, maxX.toDouble),
            Random.between(minY.toDouble, maxY.toDouble)
        )  
    }

    // Helper function to convert coordinates to grid indices
    def coordinateToGridIndex(x: Double, y: Double, cellWidth: Double = 1.0, cellHeight: Double = 1.0): (Int, Int) = {
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