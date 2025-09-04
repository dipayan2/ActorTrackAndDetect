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


// Decentralized version for comparison
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
            val xPos = 150 + col * 250  // Start at 15, then 40, 65, 90 (centers that keep areas in 0-100)
            val yPos = 150 + row * 250 // Start at 15, then 40, 65, 90
            val isLeader = droneId == 0  // First drone is leader
            
            val drone = context.spawn(
                Drone(droneId, xpos = xPos, ypos = yPos, xrange = 30, yrange = 30, isLeader = isLeader),
                s"drone$droneId"
            )
            drones += drone
            
            // Calculate actual monitoring area for logging
            val minX = math.max(0, xPos - 150)
            val maxX = math.min(1000, xPos + 150) 
            val minY = math.max(0, yPos - 150)
            val maxY = math.min(1000, yPos + 150)
            
            context.log.info(s"Created Drone $droneId at center ($xPos, $yPos) monitoring area [$minX to $maxX, $minY to $maxY]")
        }

        context.log.info("=== Setting up neighbor relationships ===")
        

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

//Centralized version for comparison
// object DroneSystem {
//     def apply(): Behavior[Generate] = Behaviors.setup { context =>
        
//         // Create 16 drones in a 4x4 grid
//         // Each drone monitors 30x30 area with 5-unit overlap
//         // Grid spacing: 30 - 5 = 25 units between drone centers
//         // Position drones to cover 0-100 range
//         val drones = scala.collection.mutable.ListBuffer[ActorRef[Event]]()
        
//         context.log.info("=== Creating 16-Drone Grid System with Centralized Neighbor Setup ===")
        
//         for (row <- 0 until 4; col <- 0 until 4) {
//             val droneId = row * 4 + col
//             val xPos = 150 + col * 250  // Start at 15, then 40, 65, 90 (centers that keep areas in 0-100)
//             val yPos = 150 + row * 250  // Start at 15, then 40, 65, 90
//             val isLeader = droneId == 0  // First drone is leader
            
//             val drone = context.spawn(
//                 Drone(droneId, xpos = xPos, ypos = yPos, xrange = 300, yrange = 300, isLeader = isLeader),
//                 s"drone$droneId"
//             )
//             drones += drone
            
//             // Calculate actual monitoring area for logging
//             val minX = math.max(0, xPos - 150)
//             val maxX = math.min(1000, xPos + 150) 
//             val minY = math.max(0, yPos - 150)
//             val maxY = math.min(1000, yPos + 150)
            
//             context.log.info(s"Created Drone $droneId at center ($xPos, $yPos) monitoring area [$minX to $maxX, $minY to $maxY]")
//         }

//         context.log.info("=== Setting up CENTRALIZED neighbor relationships ===")
        
//         // Get reference to Drone 0 (the central hub)
//         val drone0 = drones(0)
        
//         // STEP 1: Make every drone (except Drone 0) have Drone 0 as a neighbor
//         for (i <- 1 until drones.length) {
//             val currentDrone = drones(i)
//             // Add Drone 0 as neighbor to all other drones
//             currentDrone ! AddNeighbour(drone0, 0)
//             context.log.info(s"Drone $i added Drone 0 as neighbor")
//         }
        
//         // STEP 2: Make Drone 0 have all other drones as neighbors
//         for (i <- 1 until drones.length) {
//             val workerDrone = drones(i)
//             // Add each worker drone as neighbor to Drone 0
//             drone0 ! AddNeighbour(workerDrone, i)
//             context.log.info(s"Drone 0 added Drone $i as neighbor")
//         }
        
//         // STEP 3: (Optional) Keep existing adjacent neighbor relationships for redundancy
//         // This maintains your original grid-based neighbor connections alongside the centralized ones
//         for (row <- 0 until 4; col <- 0 until 4) {
//             val droneId = row * 4 + col
//             val currentDrone = drones(droneId)
//             var localNeighborCount = 0
            
//             // Connect to adjacent drones in 3x3 neighborhood (but skip Drone 0 connections since we already did those)
//             for (neighborRow <- (row-1) to (row+1); neighborCol <- (col-1) to (col+1)) {
//                 if (neighborRow >= 0 && neighborRow < 4 && neighborCol >= 0 && neighborCol < 4) {
//                     val neighborId = neighborRow * 4 + neighborCol
//                     if (neighborId != droneId) { // Don't connect to self
//                         val neighborDrone = drones(neighborId)
                        
//                         // Only add if this isn't a connection involving Drone 0 (we already handled those)
//                         if (droneId != 0 && neighborId != 0) {
//                             currentDrone ! AddNeighbour(neighborDrone, neighborId)
//                             localNeighborCount += 1
//                         }
//                     }
//                 }
//             }
            
//             if (localNeighborCount > 0) {
//                 context.log.info(s"Drone $droneId connected to $localNeighborCount additional local neighbors")
//             }
//         }

//         context.log.info("=== Finalizing centralized system setup ===")
        
//         // Signal all drones that graph creation is complete
//         drones.foreach(_ ! GraphDone)
        
//         context.log.info("=== 16-Drone CENTRALIZED System Created ===")
//         context.log.info("Centralized Network Topology:")
//         context.log.info("- Drone 0 (Central Hub): Connected to ALL 15 worker drones")
//         context.log.info("- Drones 1-15 (Workers): Each connected to Drone 0 + local neighbors")
//         context.log.info("- Communication Pattern: Star topology with Drone 0 at center")
//         context.log.info("")
//         context.log.info("Grid Layout (4x4) with centers:")
//         context.log.info("[ 0][ 1][ 2][ 3]   (15,15) (40,15) (65,15) (90,15)")
//         context.log.info("[ 4][ 5][ 6][ 7]   (15,40) (40,40) (65,40) (90,40)")
//         context.log.info("[ 8][ 9][10][11]   (15,65) (40,65) (65,65) (90,65)")
//         context.log.info("[12][13][14][15]   (15,90) (40,90) (65,90) (90,90)")
//         context.log.info("")
//         context.log.info("Neighbor Counts:")
//         context.log.info("- Drone 0: 15 neighbors (all workers)")
//         context.log.info("- Worker drones: 1 central neighbor (Drone 0) + 2-8 local neighbors")
//         context.log.info("- Total connections: 15 (central) + 44 (local grid) = 59 total connections")
//         context.log.info("=====================================")

//         Behaviors.same
//     }
// }