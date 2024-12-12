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
case class AddNeighbour(neighbour: ActorRef[GraphCreate], id: Int) extends  GraphCreate
// case class ReplyNeighbour(neighbour:)
case object GraphDone extends GraphCreate // this is to let the drones know that we do not have any more drones to add


object DroneSystem{
    def apply(): Behavior[Generate] = Behaviors.setup{ context =>

        // Say we generate three nodes and then put them as neighbours
        val drone0 = context.spawn(Drone(0,true),"drone0") // This is the leader
        val drone1 = context.spawn(Drone(1),"drone1")
        val drone2 = context.spawn(Drone(2),"drone2")

        drone0 ! AddNeighbour(drone1,1)
        drone0 ! AddNeighbour(drone2,2)
        drone1 ! AddNeighbour(drone0,0)
        drone1 ! AddNeighbour(drone2,2)
        drone2 ! AddNeighbour(drone0,0)
        drone2 ! AddNeighbour(drone1,1)
        drone0 ! GraphDone
        drone1 ! GraphDone
        drone2 ! GraphDone
        // We need to define the graph node behavior first

        Behaviors.same

    }
}




object DroneMain extends App{
    val droneSystem = ActorSystem[Generate](DroneSystem(),"StartSystem")
    droneSystem ! Generate() // this create the fixed number of drones and pre sets the graph behaviour
}

