file://<WORKSPACE>/akka-cluster/src/main/scala/sample/cluster/actordetrack/DroneManager.scala
### java.lang.AssertionError: assertion failed: NoType

occurred in the presentation compiler.

action parameters:
offset: 308
uri: file://<WORKSPACE>/akka-cluster/src/main/scala/sample/cluster/actordetrack/DroneManager.scala
text:
```scala
package sample.cluster.actordetrack
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist


object DroneManager{
    
    def apply():Behavior[Nothing] ={
        Behaviors.setup[Receptionist.Listing]{ c@@
        }
    }
}
```



#### Error stacktrace:

```
scala.runtime.Scala3RunTime$.assertFailed(Scala3RunTime.scala:8)
	dotty.tools.dotc.core.Types$TypeBounds.<init>(Types.scala:5141)
	dotty.tools.dotc.core.Types$AliasingBounds.<init>(Types.scala:5220)
	dotty.tools.dotc.core.Types$TypeAlias.<init>(Types.scala:5242)
	dotty.tools.dotc.core.Types$TypeAlias$.apply(Types.scala:5279)
	dotty.tools.dotc.core.Types$Type.bounds(Types.scala:1732)
	scala.meta.internal.pc.completions.CaseKeywordCompletion$.contribute(MatchCaseCompletions.scala:154)
	scala.meta.internal.pc.completions.Completions.advancedCompletions(Completions.scala:433)
	scala.meta.internal.pc.completions.Completions.completions(Completions.scala:183)
	scala.meta.internal.pc.completions.CompletionProvider.completions(CompletionProvider.scala:86)
	scala.meta.internal.pc.ScalaPresentationCompiler.complete$$anonfun$1(ScalaPresentationCompiler.scala:123)
```
#### Short summary: 

java.lang.AssertionError: assertion failed: NoType