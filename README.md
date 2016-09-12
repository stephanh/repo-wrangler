[![Docker](http://dockeri.co/image/stephanh/repo-wrangler)](https://hub.docker.com/r/stephanh/repo-wrangler/)
[![Build Status](https://travis-ci.org/stephanh/repo-wrangler.svg?branch=master)](https://travis-ci.org/stephanh/repo-wrangler)

# Repo Wrangler

CLI tool to assist in dealing with lots of Github repos.

It lets you:

* list all the repos in an org
* mass update repos by applying a script to them and raising a PR with the changes.
* mass merge PRs.

# Usage

Create an environment file with your github user and access token. For github enterprise you also need to add the API endpoint. For example for Github enterprise:

```
GH_URL=https://<hostname>/api/v3
GH_USER=<user>
GH_TOKEN=<token>
```

You can then run `repo-wrangler` like this `docker run --env-file gh.env -v $PWD:/scripts -v $HOME/.gitconfig:/usr/sbin/.gitconfig stephanh/repo-wrangler:latest`. This assumes that the script you want to run is in your current directory. You need need to specify the path to the script as `/scripts/<script>`.

To make it easier you can alias the command. For example `alias repo-wrangler="docker run --env-file gh.env -v $PWD:/scripts -v $HOME/.gitconfig:/usr/sbin/.gitconfig stephanh/repo-wrangler:latest"`.
