import ionroller._
import ionroller.aws.AWSClientCache
import play.api._

import scalaz._
import scalaz.stream._

object Global extends GlobalSettings {

  override def onStart(app: play.api.Application): Unit = {
    ionroller.Global.stopSignal.set(false).run

    val configurationManager = ConfigurationManager(
      ConfigurationManager.getSavedConfiguration.run
    )

    val commandManager = CommandManager(configurationManager.configurationSignal, CommandManager.getSavedDesiredSystemState.run)
    val configSignal = configurationManager.configurationSignal
    val desiredStateSignal = commandManager.desiredStateSignal
    val liveState = LiveStateCollector(AWSClientCache.getCache).liveState _

    val pipeline = Pipeline(AWSClientCache.getCache, configSignal, desiredStateSignal, liveState)

    val processes = {
      ionroller.Global.stopSignal.discrete.wye(
        commandManager.saveDesiredState
          .wye(commandManager.server)(wye.mergeHaltBoth)
          .wye(pipeline.server(ElasticBeanstalkStrategy))(wye.mergeHaltBoth)
          .wye(pipeline.saveLiveStateDiffs)(wye.mergeHaltBoth)
      )(wye.interrupt).run
    }

    ionroller.Global.backend = BackendState(configurationManager, commandManager, pipeline)

    processes.runAsync {
      case -\/(t) =>
        Logger.error("Service failed with an exception", t); Play.stop(app)
      case _ => Logger.error("Service completed for an unknown reason"); Play.stop(app)
    }
  }

  override def onStop(app: play.api.Application): Unit = {
    ionroller.Global.stopSignal.set(true).run
    ionroller.Global.backend = null
  }
}

package ionroller {

  final case class BackendState(configurationManager: ConfigurationManager, commandManager: CommandManager, pipeline: Pipeline)

  object Global {
    val stopSignal = async.signalOf(false)
    var backend: BackendState = null
  }

}
