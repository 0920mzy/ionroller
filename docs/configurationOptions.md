# Useful Configuration Options

### Notification of service deployment

Elastic Beanstalk can report of important events affecting your application, via SNS:
http://docs.aws.amazon.com/elasticbeanstalk/latest/dg/using-features.managing.sns.html

The required option is:
```json
[
    {
        "Namespace": "aws:elasticbeanstalk:sns:topics",
        "OptionName": "Notification Endpoint",
        "Value": "someone@example.com"
    }
]
```
