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
case class   BulkMerge(repos: List[String], title: String) extends Command
case class   BulkUpdate(
  repos:      List[String],
  script:     String,
  branch:     String,
  prTitle:    String,
  prMessage:  String,
  baseBranch: String = "master"
) extends Command

object Github {
  val parser = new OptionParser[Args]("repo-wrangler") {
    head("repo-wrangler")

    cmd("list-repos")
      .text("List repos in an org")
      .children(
        arg[String]("org")
          .action((s, c) => c.copy(action = ListRepos(s)))
          .text("Github organisation")
      )

    cmd("bulk-merge")
      .text("Merge the PR with the specified title in all the repos")
      .children(
        opt[String]("title")
          .abbr("t")
          .required
          .action((t, c) => c.copy(action = c.action match {
            case BulkMerge(rs, _) => BulkMerge(rs, t)
            case _                => BulkMerge(List.empty, t)
          }))
          .text("PR title"),
        arg[Seq[String]]("repos")
          .action((r, c) => c.copy(action = c.action match {
            case BulkMerge(_, t) => BulkMerge(r.toList, t)
            case _               => BulkMerge(r.toList, "")
          }))
      )

    cmd("bulk-update")
      .text("Bulk update repos by running the specified script in each repo and creating a PR with the changes")
      .children(
        opt[String]("script")
          .required
          .text("Path to script to run in each repo")
          .action((s, c) => c.copy(action = c.action match {
            case a: BulkUpdate => a.copy(script = s)
            case _             => BulkUpdate(List.empty, s, "", "", "")
          })),
        opt[String]("branch")
          .required
          .text("Branch to create for the changes")
          .action((s, c) => c.copy(action = c.action match {
            case a: BulkUpdate => a.copy(branch = s)
            case _             => BulkUpdate(List.empty, "", s, "", "")
          })),
        opt[String]("pr-title")
          .required
          .text("Title for PR with the changes")
          .action((s, c) => c.copy(action = c.action match {
            case a: BulkUpdate => a.copy(prTitle = s)
            case _             => BulkUpdate(List.empty, "", "", s, "")
          })),
        opt[String]("pr-message")
          .required
          .text("Messsage for PR with the changes")
          .action((s, c) => c.copy(action = c.action match {
            case a: BulkUpdate => a.copy(prMessage = s)
            case _             => BulkUpdate(List.empty, "", "", "", s)
          })),
        opt[String]("base-branch")
          .optional
          .text("Base branch to use when checking out the repo and make changes on")
          .action((s, c) => c.copy(action = c.action match {
            case a: BulkUpdate => a.copy(baseBranch = s)
            case _             => BulkUpdate(List.empty, "", "", "", "", s)
          })),
        arg[Seq[String]]("repos")
          .action((r, c) => c.copy(action = c.action match {
            case a: BulkUpdate => a.copy(repos = r.toList)
            case _             => BulkUpdate(r.toList, "", "", "", "")
          }))
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
      case BulkMerge(repos, title) => {
        GithubApi.bulkMerge(repos, gh, title)
          .map(_.mkString("\n"))
      }
      case BulkUpdate(rs, script, branch, prTitle, prMsg, base) =>
        GithubApi.bulkUpdate(rs, gh, settings.credentials, script, branch, prTitle, prMsg, base)
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
