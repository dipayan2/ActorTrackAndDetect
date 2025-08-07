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

object Sensor{

  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1.0
  val processNoise = 1e-5

  // New apply with timed data
  def apply(id: Int, drone: ActorRef[SensorEvent]) : Behavior[SensorEvent] = Behaviors.setup{ context =>
      context.log.info(s"Starting Sensor for Drone ${id}")
      var msgCounter = 0
      val parentDrone = drone
      val sid = id

      Behaviors.withTimers{ timers =>

        def running: Behavior[SensorEvent] = Behaviors.receiveMessage{
          case Start =>
            Behaviors.same 
          
          case Restart =>
            context.log.info(s"${sid} Node restarting ")
            timers.cancel("data-sender")
            timers.startTimerWithFixedDelay("data-sender", SendData, 40.milliseconds)
            Behaviors.same   

          case Stop =>
            timers.cancel("data-sender")
            idle
          
          case SendData =>
            val ImgRead = generateMatrixList(100)
            parentDrone ! Measurement(ImgRead, context.self)
            msgCounter = msgCounter +1
            Behaviors.same 

        }

        def idle: Behavior[SensorEvent] = Behaviors.receiveMessage{
          case Start =>
            timers.startTimerWithFixedDelay("data-sender", SendData, 1.second)
            running
          
        }
        /*
        * This is the initial state of the sensor
        */
        idle 
      }

    

  }


/**
 * A function to simulate the image we should read for a given section
*/

  def genMatrix(): Matrix2x2 = {
    Matrix2x2(
      Random.between(0.0, 10.0),
      Random.between(0.0, 10.0)
    )  
  }

/**
 * This is a list of matrix, to simulate the number of target we get
*/
  def generateMatrixList(n: Int): MatrixList = {
    MatrixList(List.fill(n)(genMatrix()))
  }

  // def generateData(mean: Double, std: Double): Double ={
  //   val r = new Random()
  //   mean + r.nextGaussian()*std
  // }


}