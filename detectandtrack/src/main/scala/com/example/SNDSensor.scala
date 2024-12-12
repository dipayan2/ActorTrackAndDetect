package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import scala.io.Source._


object Sensor{

  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1.0
  val processNoise = 1e-5

  def apply(id: Int, drone: ActorRef[SensorEvent]): Behavior[SensorEvent] = Behaviors.setup{ context =>

    context.log.info(s"Starting Sensor for Drone ${id}")
    var msgCounter = 0


    var z = generateData(constantVoltage,measurementNoise)
    drone ! Measurement(z,context.self)

    msgCounter = msgCounter+1 


    Behaviors.receiveMessage{
        case Estimate(data,old,sender)=>
            context.log.info(s"${id} Got the sensor actor started ")
            sender ! Measurement(generateData(constantVoltage,measurementNoise), context.self)
            msgCounter = msgCounter +1
            if (msgCounter > 10){
                context.log.info(s"${id} -- my sensor has sent enough data, now we rest")
                Behaviors.stopped
            } else{
                Behaviors.same
            }
    }

  }

  def generateData(mean: Double, std: Double): Double ={
    val r = new Random()
    mean + r.nextGaussian()*std
  }



}