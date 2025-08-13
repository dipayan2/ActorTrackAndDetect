package com.example

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._
import scala.util.Random
import org.apache.commons.math3.filter._
import org.apache.commons.math3.linear._
import org.apache.commons.math3.random.{JDKRandomGenerator, RandomGenerator}
import akka.actor.Actor
import scala.collection.mutable.ListBuffer

final case class Generate()

trait Event

trait GraphCreate extends Event
case class AddNeighbour(neighbour: ActorRef[GraphCreate], id: Int) extends GraphCreate
case object GraphDone extends GraphCreate // this is to let the drones know that we do not have any more drones to add

object DroneSystem {
    def apply(): Behavior[Generate] = Behaviors.setup { context =>
        
        // Create 16 drones in a 4x4 grid
        // Each drone monitors 30x30 area with 5-unit overlap
        // Grid spacing: 30 - 5 = 25 units between drone centers
        // Position drones to cover 0-100 range
        val drones = scala.collection.mutable.ListBuffer[ActorRef[Event]]()
        
        context.log.info("=== Creating 16-Drone Grid System ===")
        
        for (row <- 0 until 4; col <- 0 until 4) {
            val droneId = row * 4 + col
            val xPos = 15 + col * 25  // Start at 15, then 40, 65, 90 (centers that keep areas in 0-100)
            val yPos = 15 + row * 25  // Start at 15, then 40, 65, 90
            val isLeader = droneId == 0  // First drone is leader
            
            val drone = context.spawn(
                Drone(droneId, xpos = xPos, ypos = yPos, xrange = 30, yrange = 30, isLeader = isLeader),
                s"drone$droneId"
            )
            drones += drone
            
            // Calculate actual monitoring area for logging
            val minX = math.max(0, xPos - 15)
            val maxX = math.min(100, xPos + 15) 
            val minY = math.max(0, yPos - 15)
            val maxY = math.min(100, yPos + 15)
            
            context.log.info(s"Created Drone $droneId at center ($xPos, $yPos) monitoring area [$minX to $maxX, $minY to $maxY]")
        }

        context.log.info("=== Setting up neighbor relationships ===")
        
        // Set up neighbor relationships for 4x4 grid
        // Each drone connects to adjacent drones (up, down, left, right, and diagonally)
        for (row <- 0 until 4; col <- 0 until 4) {
            val droneId = row * 4 + col
            val currentDrone = drones(droneId)
            var neighborCount = 0
            
            // Connect to all neighboring drones in 3x3 neighborhood
            for (neighborRow <- (row-1) to (row+1); neighborCol <- (col-1) to (col+1)) {
                if (neighborRow >= 0 && neighborRow < 4 && neighborCol >= 0 && neighborCol < 4) {
                    val neighborId = neighborRow * 4 + neighborCol
                    if (neighborId != droneId) { // Don't connect to self
                        val neighborDrone = drones(neighborId)
                        currentDrone ! AddNeighbour(neighborDrone, neighborId)
                        neighborCount += 1
                    }
                }
            }
            
            context.log.info(s"Drone $droneId connected to $neighborCount neighbors")
        }

        context.log.info("=== Finalizing system setup ===")
        
        // Signal all drones that graph creation is complete
        drones.foreach(_ ! GraphDone)
        
        context.log.info("=== 16-Drone Grid System Created ===")
        context.log.info("Grid Layout (4x4) with centers:")
        context.log.info("[ 0][ 1][ 2][ 3]   (15,15) (40,15) (65,15) (90,15)")
        context.log.info("[ 4][ 5][ 6][ 7]   (15,40) (40,40) (65,40) (90,40)")
        context.log.info("[ 8][ 9][10][11]   (15,65) (40,65) (65,65) (90,65)")
        context.log.info("[12][13][14][15]   (15,90) (40,90) (65,90) (90,90)")
        context.log.info("")
        context.log.info("Coverage Details:")
        context.log.info("- Each drone monitors 30x30 area with 5-unit overlap")
        context.log.info("- Total coverage area: 0-100 x 0-100 units")
        context.log.info("- Corner drones: 3 neighbors each")
        context.log.info("- Edge drones: 5 neighbors each")
        context.log.info("- Center drones: 8 neighbors each")
        context.log.info("- Total observations: ~1,600 per second system-wide")
        context.log.info("=====================================")

        Behaviors.same
    }
}

object DroneMain extends App {
    val droneSystem = ActorSystem[Generate](DroneSystem(), "DroneGridSystem")
    droneSystem ! Generate() // this creates the 16-drone grid system
}