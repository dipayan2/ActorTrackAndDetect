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


trait SensorEvent extends Event
case object Start extends SensorEvent
case class Estimate(data: Double,old: Double, sender: ActorRef[SensorEvent]) extends SensorEvent
case object SendData extends SensorEvent
case object NextData extends SensorEvent
case class Matrix2x2(a11: Double, a12: Double, a21: Double, a22: Double)
case class MatrixList(matrices: List[Matrix2x2]) extends SensorEvent
case class Measurement(data: MatrixList, sender: ActorRef[SensorEvent]) extends SensorEvent



object Drone {
        def apply(id:Int, xpos:Int = 0, ypos:Int = 0, xrange:Int = 10, yrange:Int = 10 ,isLeader: Boolean = false): Behavior[Event] = Behaviors.setup{ context =>
            // Starting up the actor -- but wait till the graph is done
            val myID = id
            val leader = isLeader // Lets me know if I am the leader node
            val x_loc = xpos
            val y_loc = ypos
            
            val neighbors =  scala.collection.mutable.ListBuffer[ActorRef[GraphCreate]]()
            val targets = scala.collection.mutable.ListBuffer[ActorRef[TargetD]]()
            var nCount = 0
            var tgtCount = 0

            def addNeigbour(neighbour: ActorRef[GraphCreate]): Unit = {
                neighbors += neighbour // append the new element as the list
                nCount = nCount+1
            }

            def addTarget(tgt: ActorRef[TargetD]): Unit ={
                targets += tgt
                tgtCount = tgtCount + 1
                // targets.size
            }

            def targetBehaviour(data: Double): Unit={
                // Mapping the data index to the actor index 
                for (target <- targets){
                    target ! TargetData(data, context.self) // Where does the resolution takes place
                }

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
                    val sensor = context.spawn(Sensor(myID,context.self),"sensor")
                    sensor ! Start
                    // val estimator = context.spawn(KalmanEstimator(id,context.self),"estimator")
                    // Will handle data passing stuff later
                    Behaviors.receiveMessage {
                        case Measurement(data,sender) =>
                            targetBehaviour(data)
                            // May need code to resolve the targets
                            var isNew = isNewTarget(data)
                            // Drone would need to resolve the target and then send the data to the target
                            if (isNew == true){
                                val tName = s"target${tgtCount}"
                                var newtgt = context.spawn(TargetNode(tgtCount,myID,context.self),tName)
                                addTarget(newtgt)
                                // newtgt ! TargetData(data,context.self)
                            }
                            // estimator ! Measurement(data,context.self)
                            targetBehaviour(data)
                            Behaviors.same 
                        
                        case Estimate(data,old,sender) =>
                            sensor ! Estimate(data,old,context.self)
                            Behaviors.same

                        case TargetAck =>
                            context.log.info(s"Drone ${myID} received a target ACK")
                            Behaviors.same
                    }
                    // Set up a behavior receive code here
            }

            // We should add a logic for sensor crashing and then sending the trigger to restart

            graphCreation()
        }



        def isNewTarget(data: Double) : Boolean ={
             val r = new Random()
             val myInt = r.nextInt(50)
             if (myInt < 10){
                true
             } else{
                false
             }
        }

}