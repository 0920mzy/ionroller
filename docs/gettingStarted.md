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

**Make sure you have privileges to create tables in DynamoDB, create IAM roles and S3 buckets.**

**Note the  AWS Account ID for your domain.**

### Minimum Viable AWS Knowledge 

ION-Roller will do a lot of work on your behalf, however you still need to understand the underlying AWS mechanisms. Also, you can fully customise yor ION-Roller deployment via [ElasticBeanstalk Options] (http://docs.aws.amazon.com/elasticbeanstalk/latest/dg/command-options.html).

It's recommended to wrap your head around:

 - [Identity and Access Management (or IAM)](http://aws.amazon.com/iam/) roles, [giving permissions to applications] (http://docs.aws.amazon.com/IAM/latest/UserGuide/roles-usingrole-ec2instance.html), policies, and [Instance Profiles](http://docs.aws.amazon.com/IAM/latest/UserGuide/roles-usingrole-instanceprofile.html)
 - [Amazon Virtual Private Cloud (Amazon VPC) and Subnets] (http://docs.aws.amazon.com/AmazonVPC/latest/UserGuide/VPC_Subnets.html) - especially for *production* deployments
 - [VPC details specific to Elastic Beanstalk](http://docs.aws.amazon.com/elasticbeanstalk/latest/dg/AWSHowTo-vpc.html)
 - [Amazon EC2 Security Groups](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-network-security.html)
 
### Create S3 deployment bucket
[AWS] (https://console.aws.amazon.com/) ->  S3 -> Create bucket 

or if you have [AWS CLI](http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-set-up.html) installed

`aws s3  mb s3://<DEPLOYMENT_BUCKET>`


Make sure the bucket is in the same region as Elastic Beanstalk deployments. The default region to use is US Standard (or US East 1)

### Enable pulling of images from Docker Registry
Generate the [.dockercfg](https://github.com/docker/docker/blob/master/docs/sources/userguide/dockerrepos.md#account-creation-and-login) that will be used by Elastic Beanstalk to pull images from a Docker repository. **You don’t need a .dockercfg file if the specified Docker image is in a public repository.**

Upload the .dockercfg to your deployment bucket:
[AWS] (https://console.aws.amazon.com/) ->  S3  -> \<DEPLOYMENT_BUCKET\> -> Actions -> Upload

or

`aws s3 cp ~/.dockercfg s3://<DEPLOYMENT_BUCKET>`

###  Decide on the domain for your services
[AWS] (https://console.aws.amazon.com/) -> Route53 -> Hosted Zones

The url for your service should look like 
<SERVICE_NAME>.<DOMAIN_NAME>
i.e: my-service.tools.giltaws.com

**Note the  Hosted Zone ID for your domain.**

### (Optional)  Create SSH Keys to enable logging in to the Amazon EC2 instances
[AWS] (https://console.aws.amazon.com/) -> EC2 -> Key Pairs -> Create a key pair

As only one SSH key can be added for an EC2 instance, it’s recommended to create one SSH key for the team, and share it with team members.
