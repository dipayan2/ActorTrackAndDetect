package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

import scala.util.Random
import scala.io.Source._

/**
 *  Sensor Node: 
 *  1. Read the files or the time stamped file here at an interval, we can make this event based too
 *  2. Send the data to the parent drone, which will just be the image locations we have
 *      a. Currently we create a list of matrix, and send that to the sensors
 *  3. Repeat every interval 
*/




// case object Start extends SensorEvent
case object Restart extends SensorEvent
// case object SendData extends SensorEvent
case object Stop extends SensorEvent

object Sensor {

    // Constants
    val constantVoltage = 10.0
    val measurementNoise = 1.0
    val processNoise = 1e-5


    def apply(id: Int, drone: ActorRef[SensorEvent], minX: Int, maxX: Int, minY: Int, maxY: Int): Behavior[SensorEvent] = Behaviors.setup { context =>
        context.log.info(s"Starting Sensor for Drone $id - Monitoring area [$minX to $maxX, $minY to $maxY]")
        // var msgCounter = 0
        val parentDrone = drone
        val sid = id
        
   
        val monitorMinX = minX
        val monitorMaxX = maxX
        val monitorMinY = minY
        val monitorMaxY = maxY

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
                    val imgRead = generateMatrixListForArea(1000, monitorMinX, monitorMaxX, monitorMinY, monitorMaxY)
                    parentDrone ! Measurement(imgRead, context.self)
                    // msgCounter = msgCounter + 1
                    
                    // Log every 10th message to avoid spam
                    // if (msgCounter % 10 == 0) {
                    //     context.log.debug(s"Sensor $sid sent observation batch #$msgCounter to drone")
                    // }
                    
                    Behaviors.same 
            }

            def idle: Behavior[SensorEvent] = Behaviors.receiveMessage {
                case Start =>
                    timers.startTimerWithFixedDelay("data-sender", SendData, 40.milliseconds)
                    running
            }
            
            /*
            * This is the initial state of the sensor
            */
            idle 
        }
    }


    def genMatrixForArea(minX: Int, maxX: Int, minY: Int, maxY: Int): Matrix2x2 = {
        Matrix2x2(
            Random.between(minX.toDouble, maxX.toDouble),
            Random.between(minY.toDouble, maxY.toDouble)
        )  
    }


    def generateMatrixListForArea(n: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): MatrixList = {
        MatrixList(List.fill(n)(genMatrixForArea(minX, maxX, minY, maxY)))
    }


}