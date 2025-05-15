package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

import scala.util.Random
import scala.io.Source._






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
            timers.startTimerWithFixedDelay("data-sender", SendData, 1.second)
            Behaviors.same   

          case Stop =>
            timers.cancel("data-sender")
            idle
          
          case SendData =>
            val ImgRead = generateMatrixList(9)
            parentDrone ! Measurement(ImgRead, context.self)
            msgCounter = msgCounter +1
            Behaviors.same 

        }

        def idle: Behavior[SensorEvent] = Behaviors.receiveMessage{
          case Start =>
            timers.startTimerWithFixedDelay("data-sender", SendData, 1.second)
            running
          
        }

        idle // Need to have the compile part inside the behavior timer
      }

    

  }

  // def apply1(id: Int, drone: ActorRef[SensorEvent]): Behavior[SensorEvent] = Behaviors.setup{ context =>

  //   context.log.info(s"Starting Sensor for Drone ${id}")
  //   var msgCounter = 0
  //   val parentDrone = drone
  //   val sid = id
  //   var z = generateData(constantVoltage,measurementNoise)
  //   drone ! Measurement(z,context.self)

  //   msgCounter = msgCounter+1 


  //   Behaviors.receiveMessage{
  //       case SendData=>
  //           context.log.info(s"${sid} Sensor asked to send data ")
  //           drone ! Measurement(generateData(constantVoltage,measurementNoise), context.self)
  //           msgCounter = msgCounter +1
  //           if (msgCounter > 10){
  //               context.log.info(s"${sid} -- my sensor has sent enough data, now we rest")
  //               Behaviors.stopped
  //           } else{
  //               Behaviors.same
  //           }
  //   }

  // }


  def genMatrix(): Matrix2x2 = {
    Matrix2x2(
      Random.between(-10.0, 10.0),
      Random.between(-10.0, 10.0),
      Random.between(-10.0, 10.0),
      Random.between(-10.0, 10.0)
    )  
  }

  def generateMatrixList(n: Int): List[Matrix2x2] = {
    List.fill(n)(generateRandomMatrix())
  }

  def generateData(mean: Double, std: Double): Double ={
    val r = new Random()
    mean + r.nextGaussian()*std
  }


}