// //#full-example
// //#currently using example from https://commons.apache.org/proper/commons-math/userguide/filter.html
// package com.example

// import akka.actor.typed.ActorRef
// import akka.actor.typed.ActorSystem
// import akka.actor.typed.Behavior
// import akka.actor.typed.scaladsl.Behaviors
// import scala.concurrent.duration._
// import scala.util.Random
// import org.apache.commons.math3.filter._
// import org.apache.commons.math3.linear._
// import org.apache.commons.math3.random.{JDKRandomGenerator, RandomGenerator}
// import akka.actor.Actor
// import scala.collection.mutable.ListBuffer

// final case class Estimate(estimate: Double)
// final case class StartGenerate()

// object Estimator {
//   // Constants
//   val constantVoltage = 10.0
//   val measurementNoise = 1.0
//   val processNoise = 1e-5
  
//   // A = [ 1 ]
//   val A = new Array2DRowRealMatrix(Array(1.0))

//   // B = null
//   val B = new Array2DRowRealMatrix(Array(0.0))

//   // H = [ 1 ]
//   val H = new Array2DRowRealMatrix(Array(1.0))

//   // x = [ 10 ]
//   var xBar = new ArrayRealVector(Array(constantVoltage))

//   // Q = [ 1e-5 ]
//   val Q = new Array2DRowRealMatrix(Array(processNoise))

//   // P = [ 1 ]
//   var P = new Array2DRowRealMatrix(Array(1.0))

//   // R = [ 0.1 ]
//   val R = new Array2DRowRealMatrix(Array(measurementNoise))

//   // val pm = new DefaultProcessModel(A, B, Q, x, P0)
//   // val mm = new DefaultMeasurementModel(H, R)
//   // val filter = new KalmanFilter(pm, mm)

//   def apply(leader: ActorRef[LocalState], numNodes: Int): Behavior[EstimatorReceivable] = idle(leader, numNodes)
//   def apply(numNodes: Int): Behavior[EstimatorReceivable] = Behaviors.setup { context => idle(context.self, numNodes) } // Set leader as self if not explicitly passed in

//   private def idle(leader: ActorRef[LocalState], numNodes: Int): Behavior[EstimatorReceivable] = Behaviors.withTimers { timer =>
//     timer.startSingleTimer(Timeout, 5.second)
//     // Wait for measurement data
//     Behaviors.receive { (context, message) =>
//       message match {
//         case Measurement(realVec, replyTo) =>
//           sendLocalState(realVec, leader, replyTo, numNodes)
//         case Timeout =>
//           context.log.info("Timed out waiting for measurement...")
//           Behaviors.stopped
//         case _ =>
//           Behaviors.same
//       }
//     }
//   }

//   private def sendLocalState(measurement: ArrayRealVector, leader: ActorRef[LocalState], replyTo: ActorRef[Estimate], numNodes: Int): Behavior[EstimatorReceivable] = Behaviors.setup { context =>
//     // Need to do "new Array2DRowRealMatrix(<Matrix>.getData())" since inner result returns a RealMatrix 
//     leader ! LocalState(measurement, new Array2DRowRealMatrix(H.transpose().multiply(MatrixUtils.inverse(R)).multiply(H).getData()), context.self)
//     // If the actor is the leader, start receiving local states and caluculating global average
//     if (leader == context.self) {
//       calculateGlobalState(leader, replyTo, numNodes)
//     } else {
//       receiveGlobalState(leader, replyTo, numNodes)
//     }
//   }

//   private def calculateGlobalState(leader: ActorRef[LocalState], replyTo: ActorRef[Estimate], numNodes: Int): Behavior[EstimatorReceivable] = Behaviors.setup { context =>
//     var statesReceived = 0 // number of local state messages received so far
//     var avgMeasurement = new ArrayRealVector(H.getRowDimension())
//     var avgCovarianceMatrix = new Array2DRowRealMatrix(R.getRowDimension(), R.getColumnDimension())
//     var senders = new ListBuffer[ActorRef[GlobalState]]() // list of actors in leader's network we want to send the calculated global state to
//     Behaviors.receive { (context, message) =>
//       message match {
//         case LocalState(measurement, invCovarianceMatrix, sender) => 
//           statesReceived += 1
//           avgMeasurement = avgMeasurement.add(measurement)
//           avgCovarianceMatrix = avgCovarianceMatrix.add(invCovarianceMatrix)
//           if (sender != context.self)
//             senders.addOne(sender)
//           // send global state when all messages received
//           if (statesReceived == numNodes) {
//             avgMeasurement.mapDivideToSelf(numNodes)
//             avgCovarianceMatrix = new Array2DRowRealMatrix(avgCovarianceMatrix.scalarMultiply(1.0/numNodes).getData())
//             senders.foreach(sender => {
//               context.log.info(s"Sending global state to ${sender}\n")
//               sender ! GlobalState(avgMeasurement, avgCovarianceMatrix)
//             })
//             estimating(avgMeasurement, avgCovarianceMatrix, leader, replyTo, numNodes)
//           } else {
//             Behaviors.same
//           }
//         case _ =>
//           Behaviors.same
//       }
//     }
//   }

//   private def receiveGlobalState(leader: ActorRef[LocalState], replyTo: ActorRef[Estimate], numNodes: Int): Behavior[EstimatorReceivable] = Behaviors.receive { (context, message) =>
//     context.log.info(s"Received global state ${message}")
//     message match {
//       case GlobalState(avgMeasurement, avgCovarianceMatrix) => 
//         estimating(avgMeasurement, avgCovarianceMatrix, leader, replyTo, numNodes)
//       case _ =>
//         Behaviors.same
//     }
//   }

//   // Performs equations 22-25 from paper 5 (Distributed Kalman Filter with Embedded Consensus Filters)
//   // avgMeasurement is y, avgCovarianceMatrix is S
//   private def estimating(avgMeasurement: ArrayRealVector, avgCovarianceMatrix: Array2DRowRealMatrix, leader: ActorRef[LocalState], replyTo: ActorRef[Estimate], numNodes: Int): Behavior[EstimatorReceivable] = Behaviors.setup { context =>
//     // M = inv(inv(P) + S)
//     val M = new Array2DRowRealMatrix(MatrixUtils.inverse(MatrixUtils.inverse(P).add(avgCovarianceMatrix)).getData())
//     // xHat = xBar + M(y-Sx)
//     val xHat = xBar.add(M.operate(avgMeasurement.subtract(avgCovarianceMatrix.operate(xBar))))
//     // P+ = AMA'+BQB'
//     P = A.multiply(M).multiply(new Array2DRowRealMatrix(A.transpose().getData())).add(new Array2DRowRealMatrix(B.transpose().getData()))
//     // xBar = A(xHat)
//     xBar = new ArrayRealVector(A.operate(xHat))

//     // context.log.info(s"Estimator using \n y:${avgMeasurement}\n S:${avgCovarianceMatrix}\n P:${P}\n H:${H}\n xBar:${xBar}\n M:${M}\n xHat:${xHat}\n M(y-Sx):${M.operate(avgMeasurement.subtract(avgCovarianceMatrix.operate(xBar)))}")
//     context.log.info(s"Sending estimate: ${xHat.getEntry(0)}")
//     replyTo ! Estimate(xHat.getEntry(0))
//     idle(leader, numNodes)
//   }
// }

// object Generator {
//   // Constants
//   val constantVoltage = 10.0
//   val measurementNoise = 1
//   val processNoise = 1e-5
//   val numMeasurements = 5

//   // x = [ 10 ]
//   var x = new ArrayRealVector(Array(constantVoltage))

//   // Process and measurement noise vectors
//   val pNoise = new ArrayRealVector(Array(0.0))
//   val mNoise = new ArrayRealVector(Array(0.0))

//   def apply(estimator: ActorRef[Measurement]): Behavior[Estimate] = generating(estimator, 0)

//   private def generating(estimator: ActorRef[Measurement], messageCounter: Int): Behavior[Estimate] =
//   Behaviors.setup { context =>
//     // Randomly crash generator to test timeout
//     if (Random.nextInt() % 3 == 4 || messageCounter == numMeasurements) {
//       Behaviors.stopped
//     } else {
//       // simulate the process
//       pNoise.setEntry(0, processNoise * Random.nextGaussian());

//       // x = A * x + p_noise
//       x.add(pNoise)

//       // simulate the measurement
//       mNoise.setEntry(0, measurementNoise * Random.nextGaussian());

//       // z = H * x + m_noise
//       val z = x.add(mNoise)

//       context.log.info(s"Sending measurement: ${z.getEntry(0)}")
//       estimator ! Measurement(z, context.self)
//       idle(estimator, messageCounter + 1)
//     }
//   }

//   private def idle(estimator: ActorRef[Measurement], messageCounter: Int): Behavior[Estimate] = Behaviors.receive { (context, message) =>
//     context.log.info(s"Received estimate: ${message.estimate}")
//     generating(estimator, messageCounter)
//   }
// }

// object KalmanMain {
//   def apply(): Behavior[StartGenerate] =
//     Behaviors.setup { context =>
//       //#create-actors
//       val estimator1 = context.spawn(Estimator(2), "LeaderEstimator")
//       val estimator2 = context.spawn(Estimator(estimator1, 2), "NormalEstimator")
//       val generator1 = context.spawn(Generator(estimator1), "generator1")
//       val generator2 = context.spawn(Generator(estimator2), "generator2")
//       //#create-actors
//       Behaviors.same
//     }
// }

// //#main-class
// object AkkaQuickstart extends App {
//   val kalmanMain = ActorSystem[StartGenerate](KalmanMain(), "generator-estimator")
//   kalmanMain ! StartGenerate()
// }
// //#main-class
// //#full-example
