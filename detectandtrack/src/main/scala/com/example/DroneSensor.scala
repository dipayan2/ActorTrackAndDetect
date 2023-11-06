package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random

final case class Measurement(realVec: ArrayRealVector, replyTo: ActorRef[Estimate])

/**
  * A drone spawns a DroneSensor actor from which it receives sensor measurements
  */
object DroneSensor {
  // Constants
  val constantVoltage = 10.0
  val measurementNoise = 1
  val processNoise = 1e-5
  val numMeasurements = 5

  // x = [ 10 ]
  var x = new ArrayRealVector(Array(constantVoltage))

  // Process and measurement noise vectors
  val pNoise = new ArrayRealVector(Array(0.0))
  val mNoise = new ArrayRealVector(Array(0.0))

  def apply(droneRef: ActorRef[Measurement]): Behavior[Estimate] = generating(droneRef, 0)

  private def generating(droneRef: ActorRef[Measurement], messageCounter: Int): Behavior[Estimate] =
  Behaviors.setup { context =>
    // Randomly crash generator to test timeout
    if (Random.nextInt() % 3 == 4 || messageCounter == numMeasurements) {
      Behaviors.stopped
    } else {
      // simulate the process
      pNoise.setEntry(0, processNoise * Random.nextGaussian());

      // x = A * x + p_noise
      x.add(pNoise)

      // simulate the measurement
      mNoise.setEntry(0, measurementNoise * Random.nextGaussian());

      // z = H * x + m_noise
      val z = x.add(mNoise)

      context.log.info(s"Sending measurement: ${z.getEntry(0)}")
      droneRef ! Measurement(z, context.self)
      idle(droneRef, messageCounter + 1)
    }
  }

  // Wait to receive estimate back before sending new measurement
  private def idle(estimator: ActorRef[Measurement], messageCounter: Int): Behavior[Estimate] = Behaviors.receive { (context, message) =>
    context.log.info(s"Received estimate: ${message.estimate}")
    generating(estimator, messageCounter)
  }
}
