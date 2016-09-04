package wrangler
package command

import scala.util.Properties

import scalaz._, Scalaz._

import scopt.OptionParser

import wrangler.api.{Github => GithubApi, _}

case class Args(action: Command)

sealed trait Command
case object  NoCommand              extends Command
case class   ListRepos(org: String) extends Command


object Github {
  val parser = new OptionParser[Args]("repo-wrangler") {
    head("repo-wrangler")

    cmd("list-repos")
      .text("List repos in an org")
      .children(
        arg[String]("<org>")
          .action((s, c) => c.copy(action = ListRepos(s)))
          .text("Github organisation")
      )
  }


  def main(args: Array[String]): Unit = {
    val opts = parser.parse(args, Args(null))
    val result = opts.cata(
      o => parseGithubSettings >>= process(o.action),
      new Exception("").left
    )

    result.fold(
      r => println(r),
      e => {
        System.err.println(e)
        System.exit(1)
      }
    )
  }

  def process(command: Command)(settings: GithubSettings): Error[String] =
    GithubApi.connect(settings) >>= (gh => command match {
    case NoCommand => {
      new Exception(parser.renderTwoColumnsUsage).left
    }
    case ListRepos(org) => {
      GithubApi.listRepos(gh, org)
        .map(_.map(_.getFullName).mkString("\n"))
    }
  })

  def parseGithubSettings: Error[GithubSettings] = {
    Properties.envOrNone("GH_USER").cata(
      user => Properties.envOrNone("GH_TOKEN").cata(
        token => Properties.envOrNone("GH_URL").cata(
          url => EnterpriseGithubSettings(url, Credentials(user, token)).right,
          PublicGithubSettings(Credentials(user, token)).right
        ),
        new Exception("Need to set environment variable GH_TOKEN").left
      ),
      new Exception("Need to set environment variable GH_USER").left
    )
  }
}
