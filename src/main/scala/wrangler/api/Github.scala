package wrangler
package api

import java.io.File
import java.nio.file.Files

import scala.sys.process._
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._

import org.apache.commons.io.FileUtils

import scalaz._, Scalaz._

import org.kohsuke.github._

case class Credentials(user: String, token: String)

sealed trait GithubSettings {
  def credentials: Credentials
}

case class   PublicGithubSettings(credentials: Credentials)                     extends GithubSettings
case class   EnterpriseGithubSettings(apiUrl: String, credentials: Credentials) extends GithubSettings


object Github {
  sealed trait UpdateStatus
  case object  Changed                     extends UpdateStatus
  case object  NoChange                    extends UpdateStatus
  case class   FailedChange(error: String) extends UpdateStatus


  /** Connects to Github. */
  def connect(settings: GithubSettings): Error[GitHub] = safe {
    settings match {
      case PublicGithubSettings(creds)          => GitHub.connectUsingOAuth(creds.token)
      case EnterpriseGithubSettings(url, creds) => GitHub.connectToEnterprise(url, creds.token)
    }
  }

  /** Lists all the repos in an org. */
  def listRepos(gh: GitHub, orgName: String): Error[List[GHRepository]] = safe {
    val org = gh.getOrganization(orgName)
    org.listRepositories(500).asList.asScala.toList
  }

  /** Checks out the all the specified repos to the given location. */
  def bulkCheckout(
    repos:  List[String],
    gh:     GitHub,
    path:   String,
    branch: String = "master"
  ): Error[Unit] = {
    repos.traverseU { r =>
      for {
        repo   <- safe(gh.getRepository(r))
        _      <- safeShellOut(List("git", "clone", "--quiet", "--branch", branch, repo.gitHttpTransportUrl, s"$path/$r"))
      } yield ()
    }.void
  }

  /**
    * Merges the PR with the given title in all the specified repos.
    *
    * Ignores repos that do not have that PR.
    * Does not delete the PR branch.
    */
  def bulkMerge(
    repos:      List[String],
    gh:         GitHub,
    title:      String
  ): Error[List[String]] = {
    repos.traverse(r => safe {
      gh.getRepository(r).getPullRequests(GHIssueState.OPEN).asScala.toList
        .find(_.getTitle == title)
        .map(_.merge(null))
        .cata(
          _ => s"$r: Merged",
          s"$r: No matching PR"
        )
      })
  }

  /**
    * Updates all the specified repos by running the given shell script in each repo and creating a
    * PR with the changes.
    *
    * The script is run inside each repo and given the full repo name as argument. It needs to
    * commit its changes.
    * If no changes are committed the repo is ignored.
    */
  def bulkUpdate(
    repos:       List[String],
    gh:          GitHub,
    credentials: Credentials,
    scriptPath:  String,
    branch:      String,
    prTitle:     String,
    prMessage:   String,
    baseBranch:  String = "master"
  ): Error[String] = {
    val results = repos
      .map(r => (r, processRepo(r, gh, credentials, scriptPath, branch, prTitle, prMessage, baseBranch)))

     val msgs = results.map {
      case (r, \/-(Changed))         => s"* $r: Updated"
      case (r, \/-(NoChange))        => s"* $r: No change"
      case (r, \/-(FailedChange(e))) => s"* $r: Failed to update\n$e"
      case (r, -\/(e))               => s"* $r: Error processing repo:\n$e"
    }

    val status = s"""#Automator Status:\n${msgs.mkString("\n")}"""

    results.find(_._2.isLeft).cata(
      _ => new Exception(s"Some updates failed:\n$status").left,
      status.right
    )
  }

  def processRepo(
    repoName:    String,
    gh:          GitHub,
    credentials: Credentials,
    scriptPath:  String,
    branch:      String,
    prTitle:     String,
    prMessage:   String,
    baseBranch:  String
  ): Error[UpdateStatus] = for {
    repo   <- safe(gh.getRepository(repoName))
    _      <- safe(println(s"Processing $repoName"))
    status <- updateRepo(repo, credentials, scriptPath, branch, prTitle, prMessage, baseBranch)
    _      <- if (status == Changed) safe(repo.createPullRequest(prTitle, branch, baseBranch, prMessage)).void
              else ().right
  } yield status

  def updateRepo(
    repo:        GHRepository,
    credentials: Credentials,
    scriptPath:  String,
    branch:      String,
    prTitle:     String,
    prMessage:   String,
    baseBranch:  String
  ): Error[UpdateStatus] = for {
    path   <- safe(Files.createTempDirectory(s"""automator_${repo.getFullName.replace("/", "_")}_""")).map(_.toAbsolutePath)
    url     = repo.gitHttpTransportUrl.replace("://", s"://${credentials.user}:${credentials.token}@")
    _      <- safeShellOut(List("git", "clone", "--quiet", "--single-branch", "--depth", "1", "--branch", baseBranch, url, path.toString), true, Option(credentials.token))
    _      <- safeShellOut(List("git", "--git-dir", s"${path}/.git", "checkout", "-b", branch)).map(_.mkString(""))
    result <- shellOut(List(scriptPath, repo.getFullName), false, path.toString)
    base   <- safeShellOut(List("git", "--git-dir", s"${path}/.git", "rev-parse", baseBranch)).map(_.mkString(""))
    update <- safeShellOut(List("git", "--git-dir", s"${path}/.git", "rev-parse", branch)).map(_.mkString(""))
    _      <- if (base == update) ().right
              else safeShellOut(List("git", "--git-dir", s"${path}/.git", "push", "origin", branch), true, Option(credentials.token)).void
    _      <- safe(FileUtils.deleteDirectory(new File(path.toString)))
  } yield {
    if (result._1 != 0)      FailedChange(result._2.mkString("\n").replace(credentials.token, "xxxx"))
    else if (base == update) NoChange
    else                     Changed
  }

  def shellOut(cmd: List[String], quiet: Boolean = true, workDir: String = System.getProperty("user.dir")): Error[(Int, List[String])] = safe {
    val output    = ListBuffer.empty[String]
    val cmdLogger = ProcessLogger(
      (o: String) => {
        output.append(o)
        if (!quiet) System.out.println(o)
      },
      (e: String) => {
        output.append(e)
        if (!quiet) System.err.println(e)
      }
    )

    val exitValue = Process(cmd, new File(workDir)) ! cmdLogger
    (exitValue, output.toList)
  }

  def safeShellOut(cmd: List[String], quiet: Boolean = true, sensitive: Option[String] = None): Error[List[String]] =
    shellOut(cmd, quiet).flatMap { case (exitCode, output) =>
      if (exitCode == 0) output.right
      else {
        val msg = s"""Failed to ${cmd.mkString(" ")} :\n${output.mkString("\n")}"""
        val sanitisedMsg = sensitive.cata(
          s => msg.replaceAll(s, "xxxx"),
          msg
        )
        new Exception(sanitisedMsg).left
      }
    }

  def safe[T](action: => T): Error[T] = \/.fromTryCatchNonFatal(action)
}
