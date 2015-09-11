# Getting started 

## Build Docker image 
To work with ION-Roller you need to be able to build a Docker image for your application and push it into Docker registry. 

Install [VirtualBox](https://www.virtualbox.org/), [docker-machine] (https://docs.docker.com/machine) and Docker.

Get familiar with Docker and dockerize your service: [user guide](https://docs.docker.com/userguide/) & [dockerizing an app](https://docs.docker.com/userguide/dockerizing/).

If you develop in Scala, install [sbt-native-packager](http://www.scala-sbt.org/sbt-native-packager/gettingstarted.html#installation) and learn how to use [Docker plugin](http://www.scala-sbt.org/sbt-native-packager/formats/docker.html).

Learn how to work with [Docker Registry](https://hub.docker.com/) ([user guide](https://github.com/docker/docker/blob/master/docs/sources/userguide/dockerrepos.md)), create an account and push your image.

## Prepare AWS account

ION-Roller CLI doesn't require AWS CLI to run. However AWS CLI comes in handy to configure AWS Security Credentials and to set ION-Roller service.

Follow the steps to set up [AWS Command Line Interface](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-set-up.html), especially 

 - [Sign Up for Amazon Web Services] (http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-set-up.html#cli-signup)
 - [Installing the AWS Command Line Interface](http://docs.aws.amazon.com/cli/latest/userguide/installing.html)
 - [Configuring the AWS Command Line Interface](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html)

*Make sure you have privileges to create tables in DynamoDB and create IAM roles.*

## Minimum Viable AWS Knowledge 

ION-Roller will do a lot of work on your behalf, however you still need to understand the underlying AWS mechanisms. Also, you can fully customise yor ION-Roller deployment via [ElasticBeanstalk Options] (http://docs.aws.amazon.com/elasticbeanstalk/latest/dg/command-options.html).

It's recommended to wrap your head around:

 - [Identity and Access Management (or IAM)](http://aws.amazon.com/iam/) roles, [giving permissions to applications] (http://docs.aws.amazon.com/IAM/latest/UserGuide/roles-usingrole-ec2instance.html), policies, and [Instance Profiles](http://docs.aws.amazon.com/IAM/latest/UserGuide/roles-usingrole-instanceprofile.html)
 - [Amazon Virtual Private Cloud (Amazon VPC) and Subnets] (http://docs.aws.amazon.com/AmazonVPC/latest/UserGuide/VPC_Subnets.html) - especially for *production* deployments
 - [VPC details specific to Elastic Beanstalk](http://docs.aws.amazon.com/elasticbeanstalk/latest/dg/AWSHowTo-vpc.html)
 - [Amazon EC2 Security Groups](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-network-security.html)
 
