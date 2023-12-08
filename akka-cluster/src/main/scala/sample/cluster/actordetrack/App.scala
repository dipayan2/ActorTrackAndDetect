package sample.cluster.actordetrack

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Routers
import akka.cluster.typed.Cluster
import com.typesafe.config.ConfigFactory

object App {

  // val DroneServiceKey = ServiceKey[DroneNode.Event]("DroneService")

  //final case class DroneService(replyTo: ActorRef[DroneNode.Start])
  
  private object Drone{
    def apply(): Behavior[Nothing] = Behaviors.setup[Nothing]{ ctx =>
      val cluster = Cluster(ctx.system)
      if (cluster.selfMember.hasRole("Drone")){
        // Spawning a drone
          val drone = ctx.spawn(DroneNode(),"Drone")
          // registering it to the key
          // ctx.system.receptionist ! Receptionist.Register(DroneServiceKey,drone)
      }


      if(cluster.selfMember.hasRole("DroneManager")){
        // Look at all the drone that are joining
        // Ignore for now
        ctx.spawn(DroneManager(),"DroneManager")
      }


      Behaviors.empty[Nothing]

    }
  }

  def main(args: Array[String]): Unit = {
      startup("DroneManager",25251) 
      startup("Drone", 25255)

      startup("Drone", 25256)

      startup("Drone", 25257)
      // startup("DroneManager", 0)
  }
  private def startup(role:String, port: Int): Unit ={
     val config = ConfigFactory
      .parseString(s"""
      akka.remote.artery.canonical.port=$port
      akka.cluster.roles = [$role]
      """)
      .withFallback(ConfigFactory.load("drone"))
    
      ActorSystem[Nothing](Drone(), "ClusterSystem", config)
  }
}
