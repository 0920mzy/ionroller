# ION-Roller
AWS Immutable Deployment framework for web services

# Introduction
ION-Roller is a service that manages the lifecycle of web services in AWS,
providing a deployment system that supports "immutable environments", where
the new environment is set up without tearing down the old environment in the
process.

Traffic is then gradually migrated between the systems.

We plan to release the software as open-source in early June 2015.

See [this InfoQ article](http://www.infoq.com/articles/gilt-deploying-microservices-aws) for
more details on the goals and design of the system.

