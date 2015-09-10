package ionroller.deployment.eb.v1

import ionroller.{DockerImage, PortMapping}

final case class DockerConfig(dockerrun: DockerrunAWSJson, dockerfile: Dockerfile)

final case class Dockerfile(from: DockerImage, expose: Seq[PortMapping], cmd: Seq[String]) {

  override def toString = {
    val str = "FROM " + from.toString + "\n" +
      "EXPOSE " + expose.map(portMapping => portMapping.containerPort).mkString(" ") + "\n"

    if (!cmd.isEmpty) str + "CMD [" + cmd.map(arg => "\"" + arg + "\"").mkString(", ") + "]"
    else str

  }

  def getName = {
    "Dockerfile"
  }
}
