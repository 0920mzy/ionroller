package ionroller

final case class TimelineData(name: TimelineName, systemConfig: SystemConfiguration, timelineConfig: TimelineConfiguration, desired: DesiredTimelineState, liveState: LiveTimelineState, curEnvironment: Option[(EBSSetup, LiveInfoForSetup)], nextEnvironment: Option[(EBSSetup, LiveInfoForSetup)], enabled: Boolean)
