package com.example
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
// import akka.actor.typed.receptionist.Receptionist
// import org.apache.commons.math3.linear.ArrayRealVector

import scala.concurrent.duration._
import akka.actor.Actor


trait SensorEvent extends Event
case object Start extends SensorEvent
case class Estimate(data: Double,old: Double, sender: ActorRef[SensorEvent]) extends SensorEvent
case class Measurement(data: Double, sender: ActorRef[SensorEvent]) extends SensorEvent


object Drone {
        def apply(id:Int, isLeader: Boolean = false): Behavior[Event] = Behaviors.setup{ context =>
            // Starting up the actor -- but wait till the graph is done
            val myID = id
            val leader = isLeader // Lets me know if I am the leader node
            var neighbors =  scala.collection.mutable.ListBuffer[ActorRef[GraphCreate]]()
            var nCount = 0

            def addNeigbour(neighbour: ActorRef[GraphCreate]): Unit = {
                neighbors = neighbors:+neighbour // append the new element as the list
                nCount = nCount+1
            }

            def graphCreation(): Behavior[Event] =  Behaviors.receiveMessage{ 

                case AddNeighbour(neighbour, nid) =>
                    addNeigbour(neighbour)
                    context.log.info(s"${nid} node got added in my ${myID} list")
                    Behaviors.same
                
                case GraphDone =>
                    context.log.info(s"${myID} -- All node added")
                    startNode
            }
            // Logic when the node is started. Create the sensor nodes and fireup the estimator node too
            def startNode: Behavior[Event] = Behaviors.setup { context =>
                    context.log.info(s"${myID} -- Node started")
                    val sensor = context.spawn(Sensor(id,context.self),"sensor")
                    val estimator = context.spawn(KalmanEstimator(id,context.self),"estimator")
                    // Will handle data passing stuff later
                    Behaviors.receiveMessage {
                        case Measurement(data,sender) =>
                            estimator ! Measurement(data,context.self)
                            Behaviors.same 
                        
                        case Estimate(data,old,sender) =>
                            sensor ! Estimate(data,old,context.self)
                            Behaviors.same
                    }
                    // Set up a behavior receive code here
            }

            // The behaviour to start with

            graphCreation()
        }

}