# Traffic redirection

The default method of moving traffic between old and new environments is by changing the configured DNS entry, so that it starts to report the addresses of the new environment load balancer.

This causes some side-effects:
 - Traffic movement is dependent on clients re-resolving DNS, and then reconnecting.
 - The movement of traffic between environments cannot be easily controlled.
 - New ELBs are not "warmed up" initially after the environment is set up.

To help with these issues, we offer the option of permanently running an ELB that exists independently of individual environments.

## Changes in environment
The environment has one change; the security groups which are allowed to talk to server instances is altered, to allow the extra ELB to talk to them.

## DNS naming
The DNS name set up by Route53 does not change behaviour; it still points to a new environment immediately after it becomes healthy. You may either keep this (it will always point at the latest release), and point another DNS entry at the external load balancer, or update the Route53 entry to always point at the new release.

To update the Route53 entry:
 - Disable the current weighted entry for your DNS entry (tagged with the name "ionroller"), by setting its weight to zero.
 - Add a new entry (with weighted routing) with a non-zero weight, as an ALIAS to the external load balancer.

## Traffic migration
When the new environment becomes healthy, traffic can then be moved from the old environment to the new environment. The traffic is actually moved instance-by-instance, as old instances are unregistered from the ELB, and new instances are added.

 1. The ELB has one instance added from the new environment.
 2. The ELB has one instance removed from the old environment.
 3. The service waits for a configured number of minutes.
 4. Steps 1-3 are repeated until all instances from the new environment are registered, and all instances from the old environment are unregistered.
 5. The new environment has its autoscaling group reconfigured, so that any change in the set of instances is applied to the load balancer (in case of future autoscaling activities).

## Rollback
If you request a rollout to an environment that previously handled the full set of traffic, the complete set of traffic will be moved immediately. This allows for a relatively quick rollback, if issues are discovered with the new environment. 

## Effect summary
Traffic is moved instance-by-instance, so its benefit depends on the size of your autoscaling group. The larger the group, the more granular the move in traffic. But this is exactly the situation where gradual traffic moves are valuable to detect issues during rollout.
