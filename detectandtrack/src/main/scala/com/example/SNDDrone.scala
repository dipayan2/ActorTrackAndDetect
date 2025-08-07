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
case class Matrix2x2(x: Double, y: Double) extends SensorEvent
case class MatrixList(matrices: List[Matrix2x2]) extends SensorEvent
case class Measurement(data: MatrixList, sender: ActorRef[SensorEvent]) extends SensorEvent
case class TargetData(id: Int, ref:ActorRef[TargetD], x:Double, y:Double, rowX:Int, rowY:Int) extends SensorEvent


object Drone {
        def apply(id:Int, xpos:Int = 0, ypos:Int = 0, xrange:Int = 10, yrange:Int = 10 ,isLeader: Boolean = false): Behavior[Event] = Behaviors.setup{ context =>
            // Starting up the actor -- but wait till the graph is done
            val myID = id
            val leader = isLeader // Lets me know if I am the leader node
            val x_loc = xpos
            val y_loc = ypos
            
            val neighbors =  scala.collection.mutable.ListBuffer[ActorRef[GraphCreate]]()
            val targets = scala.collection.mutable.ListBuffer[TargetData]()
            val targetMap = scala.collection.mutable.Map[(Int,Int), List[Int]]()
            var nCount = 0
            var tgtCount = 0

            def addNeigbour(neighbour: ActorRef[GraphCreate]): Unit = {
                neighbors += neighbour // append the new element as the list
                nCount = nCount+1
            }

            def addTarget(tgt: TargetData): Unit ={
                targets += tgt    
                val updatedList = targetMap.getOrElse((tgt.rowX,tgt.rowY), List()) :+ tgtCount
                targetMap.update((tgt.rowX,tgt.rowY), updatedList)
                tgtCount = tgtCount + 1
                // targets.size
            }

            def targetBehaviour(data: MatrixList): Unit={

                for(idx <- data.matrices.indices){
                    // We go through the data
                    val (obsX, obsY) = (data.matrices(idx).x, data.matrices(idx).y)
                    val (rowX,rowY) = coordinateToGridIndex(obsX,obsY)

                    if(targetMap.contains((rowX,rowY))){
                        for (targIdx <- targetMap((rowX,rowY)) ){
                            targets(targIdx).ref ! TargetData(data.matrices(idx), context.self)
                        }
                    } 
                    else{
                        val tref = context.spawn(TargetNode(tgtCount,myID, context.self),s"target-node-$tgtCount")
                        val tdata = TargetData(tgtCount,tref,obsX, obsY,rowX,rowY)
                        addTarget(tdata)
                    }
                }

                // Mapping the data index to the actor index 
                // for (idx <- targets.indices){
                //     targets(idx).ref ! TargetData(data.matrices(idx%9), context.self) // Where does the resolution takes place
                // }
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
            /**
             * The operation of the drone starts here
             * 1. We start the sensor for this node, and start receiving information from the sensor
             * 2. Assume the following algorithm -- that we can identify the expected object location, and then find an object there
            */
            def startNode: Behavior[Event] = Behaviors.setup { context =>
                    context.log.info(s"${myID} -- Node started")
                    /**
                     * Starting the timed sensor node -- this will supply the information to the drone
                    */
                    val sensor = context.spawn(Sensor(myID,context.self),"sensor")
                    sensor ! Start
                    /**
                     * Create a set of dummy targets of size 9
                    */

                    // for (i <- 0 to 1000){
                    //     val target = context.spawn(TargetNode(tgtCount,myID, context.self),s"target-node-$tgtCount")
                    //     addTarget(target)
                    // }
                    /**
                     * End if dummy targets
                    */
                    Behaviors.receiveMessage {
                        case Measurement(data,sender) =>
                            /**
                             * Create a process to get the location of the objects, which for now is the value it returns
                             * Assume we have the updated states for each target
                            */

                            targetBehaviour(data)
                            
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


        def coordinateToGridIndex(x: Double, y: Double, cellWidth: Double=2.0, cellHeight: Double=2.0): (Int, Int) = {
            val column = (x / cellWidth).toInt
            val row = (y / cellHeight).toInt
            (row, column)
        }




}