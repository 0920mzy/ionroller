[![Circle CI](https://circleci.com/gh/gilt/ionroller.svg?style=svg)](https://circleci.com/gh/gilt/ionroller)
# ION-Roller: AWS Immutable Deployment framework for web services

ION-Roller is a service (API, web app and CLI tool) that leverages Amazon’s Elastic Beanstalk and underlying CloudFormation framework capabilities to deploy Docker images to EC2 instances.

## Features

 - Automated resource management
 - Safe immutable deployments
 - Do healthchecks, move traffic to new deployment
 - Phased rollout
 - Provides fast rollback (old version still deployed)
 - Visibility
   * All releases/rollbacks/changes to envs are logged
   * Current state visible to everybody on team
 - Independent of language & dev environment (Docker!)
 - Deployment configuration management (and history tracking)
 - ‘Testing in production’ easy to implement


```bash
ionroller release my-service 0.0.1
```

### Check out the [demo](https://drive.google.com/file/d/0B4LFRaB4aCbcRFRra0JOcUJnRVk/view?usp=sharing)!

<iframe src="https://drive.google.com/file/d/0B4LFRaB4aCbcRFRra0JOcUJnRVk/preview" width="640" height="480"></iframe>


For a more thorough explanation of motivation and the concepts behind ION-Roller check [InfoQ article] (http://www.infoq.com/articles/gilt-deploying-microservices-aws).

----------

 - [Getting Started] (docs/gettingStarted.md)
 - [Setting up ION-Roller](docs/serviceSetup.md)
 - [Deploying services] (docs/deployingServices.md)
 - [Traffic redirection] (docs/trafficRedirection.md)
 - [Development](docs/development.md)
 - [REST API - Apidoc](http://www.apidoc.me/gilt/ionroller-api)


