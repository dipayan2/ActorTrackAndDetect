package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import scala.io.Source._

case class Measurement(measurement: ArrayRealVector) extends DroneNode.KalmanMessage

/**
  * A drone spawns a DroneSensor actor from which it receives sensor measurements
  */
object DroneSensor {
  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1
  val processNoise = 1e-5
  println(System.getProperty("user.dir"))
  val droneInput = fromFile("drone.txt").getLines

  // x = [ 10 ]
  // var x = new ArrayRealVector(Array(constantVoltage))

  // Process and measurement noise vectors
  val pNoise = new ArrayRealVector(Array(0.0))
  val mNoise = new ArrayRealVector(Array(0.0))

  def apply(droneRef: ActorRef[DroneNode.KalmanMessage]): Behavior[DroneNode.KalmanMessage] = generating(droneRef, 0)

  private def generating(droneRef: ActorRef[DroneNode.KalmanMessage], messageCounter: Int): Behavior[DroneNode.KalmanMessage] =
  Behaviors.setup { context =>
    // Randomly crash generator to test timeout
    if (Random.nextInt() % 3 == 4) {
      context.log.info("Crashing drone")
      Behaviors.stopped
    } else {
      // simulate the process
      // pNoise.setEntry(0, processNoise * Random.nextGaussian());
      if (droneInput.hasNext) {
        val strInput = droneInput.next()
        var numInput = 0.0
        try {
          numInput = strInput.toFloat
        } catch {
          case e: Exception => {
            context.log.info(s"Invalid input ${strInput} shutting down drone")
            Behaviors.stopped
          }
        }
        val z = new ArrayRealVector(Array(numInput))
        context.log.info(s"Sending measurement: ${z.getEntry(0)}")
        droneRef ! Measurement(z)
        idle(droneRef, messageCounter + 1)
      } else {
        context.log.info(s"Reached end of input file, shutting down drone")
        Behaviors.stopped
      }
    }
  }

  // Wait to receive estimate back before sending new measurement
  private def idle(estimator: ActorRef[DroneNode.KalmanMessage], messageCounter: Int): Behavior[DroneNode.KalmanMessage] = Behaviors.receive { (context, message) =>
    message match {
      case Estimate(estimate) =>
        context.log.debug(s"Received estimate: ${estimate}")
        generating(estimator, messageCounter)
    }
  }
}
