package ionroller

final case class SystemData(config: SystemConfiguration, desired: DesiredSystemState, current: LiveSystemState)
