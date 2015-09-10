package ionroller.cmd

import net.bmjames.opts._
import net.bmjames.opts.types.{ParserPrefs, Parser}
import org.joda.time.format.ISODateTimeFormat

import scalaz._
import scalaz.Scalaz._
import scalaz.concurrent.Task

object IonrollerParser {
  val dtParser = ISODateTimeFormat.dateTimeParser

  val releaseOpts: Parser[Command] = {
    Apply[Parser].apply4(
      strArgument(metavar("SERVICE"), help("Name of service")),
      strArgument(metavar("VERSION"), help("Version of service to release")),
      optional(strOption(metavar("CONFIG_ISO_8601_DATE"), short('c'), long("config"), help("Version of deployment configuration")).map(dtParser.parseDateTime)),
      switch(long("emergency_deploy"), help("Perform emergency deployment without contacting running ION-Roller service"))
    )(CmdRelease.apply)
  }

  val dropOpts: Parser[Command] = {
    Apply[Parser].apply4(
      strArgument(metavar("SERVICE"), help("Name of service")),
      strArgument(metavar("VERSION"), help("Version of service to drop")),
      optional(strOption(metavar("CONFIG_ISO_8601_DATE"), short('c'), long("config"), help("Version of deployment configuration")).map(dtParser.parseDateTime)),
      switch(long("force"))
    )(CmdDrop.apply)
  }

  val eventsOpts: Parser[Command] =
    Apply[Parser].apply4(
      strArgument(metavar("SERVICE"), help("Name of service")),
      optional(strArgument(metavar("VERSION"), help("Version of service for which to show events"))),
      optional(strOption(metavar("ISO_8601_DATE"), short('f'), long("from"), help("Show events from this time")).map(dtParser.parseDateTime)),
      optional(strOption(metavar("ISO_8601_DATE"), short('t'), long("to"), help("Show events until this time")).map(dtParser.parseDateTime))
    )(CmdEvents.apply)

  val configOpts: Parser[Command] =
    Apply[Parser].apply2(
      strArgument(metavar("SERVICE"), help("Name of service")),
      optional(strOption(metavar("ISO_8601_DATE"), short('t'), long("timestamp"), help("Show config from this time")).map(dtParser.parseDateTime))
    )(CmdConfig.apply)

  val setConfigOpts: Parser[Command] =
    Apply[Parser].apply2(
      strArgument(metavar("SERVICE"), help("Name of service")),
      optional(strArgument(metavar("FILE"), help("Path to file with JSON config; if not specified, it will read from standard input")))
    )(CmdSetConfig.apply)

  val deleteConfigOpts: Parser[Command] =
    strArgument(metavar("SERVICE"), help("Name of service")).map(CmdDeleteConfig.apply)

  val configsOpts: Parser[Command] =
    Apply[Parser].apply3(
      strArgument(metavar("SERVICE"), help("Name of service")),
      optional(strOption(metavar("ISO_8601_DATE"), short('f'), long("from"), help("Show events from this time")).map(dtParser.parseDateTime)),
      optional(strOption(metavar("ISO_8601_DATE"), short('t'), long("to"), help("Show events until this time")).map(dtParser.parseDateTime))
    )(CmdConfigs.apply)

  val currentOpts: Parser[Command] =
    strArgument(metavar("SERVICE"), help("Name of service")).map(CmdCurrent.apply)

  val setBaseUrlOpts: Parser[Command] =
    strArgument(metavar("BASE_URL"), help("ION-Roller service base URL")).map(CmdSetBaseUrl.apply)

  val setCliUpdateUrlOpts: Parser[Command] =
    strArgument(metavar("UPDATE_URL"), help("ION-Roller CLI update URL")).map(CmdSetCliUpdateUrl.apply)

  val parseOpts: Parser[Command] =
    subparser(
      command(
        "release",
        info(
          releaseOpts,
          progDesc("Release version: ionroller release SERVICE VERSION [-c|--conf ISO_8601_DATE]")
        )
      ),
      command(
        "drop",
        info(
          dropOpts,
          progDesc("Drop version: ionroller drop SERVICE VERSION [-c|--conf ISO_8601_DATE] [--force]")
        )
      ),
      command(
        "events",
        info(
          eventsOpts,
          progDesc("Show events for service: ionroller events SERVICE [VERSION] [-f|--from ISO_8601_DATE] [-t|--to ISO_8601_DATE]")
        )
      ),
      command(
        "config",
        info(
          configOpts,
          progDesc("Get configuration: ionroller config SERVICE [-t,--timestamp ISO_8601_DATE]")
        )
      ),
      command(
        "set_config",
        info(
          setConfigOpts,
          progDesc("Set configuration: ionroller set_config SERVICE [FILE]")
        )
      ),
      command(
        "delete_config",
        info(
          deleteConfigOpts,
          progDesc("Delete configuration: ionroller delete_config SERVICE")
        )
      ),
      command(
        "configs",
        info(
          configsOpts,
          progDesc("Get configuration history timestamps: ionroller configs SERVICE [-f|--from ISO_8601_DATE] [-t|--to ISO_8601_DATE]")
        )
      ),
      command(
        "current",
        info(
          currentOpts,
          progDesc("Get current version: ionroller current SERVICE")
        )
      ),
      command(
        "update",
        info(
          Parser.pure(CmdUpdate),
          progDesc("Update ionroller CLI")
        )
      ),
      command(
        "setup",
        info(
          Parser.pure(CmdSetup),
          progDesc("Setup ionroller service")
        )
      ),
      command(
        "version",
        info(
          types.Parser.pure(CmdVersion),
          progDesc("ionroller version")
        )
      ),
      command(
        "set_base_url",
        info(
          setBaseUrlOpts,
          progDesc("Set ionroller base URL")
        )
      ),
      command(
        "set_client_update_url",
        info(
          setCliUpdateUrlOpts,
          progDesc("Set ionroller CLI update URL")
        )
      ),
      help("Command to run")
    )

  def paramAndCmdOpts: Parser[CmdAndParams] =
    Apply[Parser].apply2(
      parseOpts,
      optional(strOption(metavar("BASE_URL"), long("base"), help("Base URL for ionroller service")))
    ) { case (cmd, base) => CmdAndParams(cmd, base) }

  def cmdAndParams(args: List[String]): Task[CmdAndParams] = Task.delay(customExecParser(
    args,
    "ionroller",
    ParserPrefs(multiSuffix = "", showHelpOnError = true),
    info(paramAndCmdOpts <*> helper, header(s"v${ionroller.BuildInfo.version}"))
  )).handle {
    case e: IllegalArgumentException => sys.error("Incorrect format for date.")
  }

}
