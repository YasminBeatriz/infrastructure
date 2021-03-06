#!groovy

import groovy.json.*
import groovy.transform.Field

pipelineParameters = load 'infrastructure/pipeline/build/parameters.groovy'

@Field String triggeredRepoName
@Field Map gitRepos
@Field Map buildInfo
@Field String buildTimestamp

def initialize() {
  properties([parameters(utils.convertToJenkinsParameters(pipelineParameters)),
              [$class: 'jenkins.model.BuildDiscarderProperty', strategy:
               [$class: 'LogRotator', numToKeepStr: '2']]])

  String triggeredRepoURL = scm.userRemoteConfigs.get(0).url
  triggeredRepoName =
    triggeredRepoURL.tokenize('/').last().tokenize('.').first()
  echo "Build triggered from $triggeredRepoName repository"

  utils.setGithubStatus(
    triggeredRepoName, 'Initializing pipeline', 'PENDING')

  gitRepos = utils.getGitRepos(triggeredRepoName)

  buildInfo = [:]
}

def authorize() {
  utils.setGithubStatus(
    triggeredRepoName, 'Checking for author authorization', 'PENDING')

  author = env.CHANGE_AUTHOR
  echo "Change author: $author"

  if (author == null) {
    echo 'Executed from interface or by direct push to branch, ' +
      'assuming user is authorized'
    return
  }

  List authorizedUsers = []
  if (params.GITHUB_AUTHORIZED_USERS) {
    authorizedUsers = params.GITHUB_AUTHORIZED_USERS.tokenize()
  } else {
    echo("Retrieving authorized users from GitHub organization " +
         params.GITHUB_ORGANIZATION_NAME)
    String orgMembersURL = ("https://api.github.com/orgs/" +
                            "$params.GITHUB_ORGANIZATION_NAME/public_members")
    response = httpRequest(url: orgMembersURL,
                           authentication: 'github-user-pass-credentials')
    JsonSlurper jsonSlurper = new JsonSlurper()
    List responseList = jsonSlurper.parseText(response.content)
    for (Map user : responseList) {
      authorizedUsers += user.login
    }
  }
  echo "Authorized GitHub users: " + authorizedUsers.join(", ")

  if (author in authorizedUsers) {
    echo "Author is authorized"
  } else {
    utils.setGithubStatus(
      triggeredRepoName, 'Waiting for manual authorization', 'PENDING')
    input message: "$author does not have write access to the repository"
  }
}

def validate() {
  utils.setGithubStatus(
    triggeredRepoName, 'Executing file validations', 'PENDING')

  if (triggeredRepoName == 'infrastructure') {
    echo "No validation for $triggeredRepoName repository"
    return
  }

  utils.checkoutRepo('builds', gitRepos)
  if (triggeredRepoName == 'versions') {
    utils.checkoutRepo('versions', gitRepos)
  }

  String dirToValidate = pwd() + "/$triggeredRepoName"
  Map validations = [
    YAMLlint: {
      sh "./scripts/validate_yamls.py -d $dirToValidate"
    }
  ]

  if (triggeredRepoName == 'builds') {
    validations['Pylint'] = {
      String pythonFiles = sh(script: 'find -name "*.py"',
                              returnStdout: true).replaceAll('\n', ' ')
      sh "pylint $pythonFiles"
    }
    validations['Unit tests'] = {
      sh 'nosetests tests/unit'
    }
  } else if (triggeredRepoName == 'versions') {
    validations['RPMlint'] = {
      sh "./scripts/validate_rpm_specs.py -d $dirToValidate"
    }
  }

  dir('builds') {
    parallel(validations)
  }
}

def buildPackages() {
  if (triggeredRepoName) {
    utils.setGithubStatus(
      triggeredRepoName, 'Building packages', 'PENDING')
  }

  buildInfo.build_packages_finished = false
  buildInfo.log_url = currentBuild.getAbsoluteUrl() + 'consoleFull'
  String VERSIONS_REPO_URL =
    gitRepos['versions'].userRemoteConfigs.get(0).url
  String VERSIONS_REPO_DIR = "versions_build-packages"
  String VERSIONS_REPO_PATH =
    "$params.BUILDS_WORKSPACE_DIR/repositories/$VERSIONS_REPO_DIR"

  deleteDir()
  utils.checkoutRepo('builds', gitRepos)
  dir('builds') {
    // Tell mock to use a different mirror/repo.
    // This could be used to:
    // - speedup the chroot installation
    // - use a different version of CentOS
    // - workaround any issue with CentOS official mirrors
    if (params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL) {
      utils.replaceInFile(params.MOCK_CONFIG_FILE,
                          params.MAIN_CENTOS_REPO_RELEASE_URL,
                          params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL)
    }
  }

  checkoutResult = utils.checkoutRepo('versions', gitRepos)
  versionsRepoCommitId = checkoutResult['GIT_COMMIT']
  echo(versionsRepoCommitId)

  lock(resource: "build-packages_workspace_$env.NODE_NAME") {
    dir("$params.BUILDS_WORKSPACE_DIR/mock_build") {
      deleteDir()
    }
    sh "rm -f $params.BUILDS_WORKSPACE_DIR/*.log"

    dir('builds') {
      echo 'Building packages'
      String packagesParameter = ''
      if (params.PACKAGES) {
        packagesParameter = "--packages $PACKAGES"
      }
      catchError {
        sh """\
python host_os.py \\
       --work-dir $params.BUILDS_WORKSPACE_DIR \\
       build-packages \\
           --force-rebuild \\
           --keep-build-dir \\
           --packages-metadata-repo-url $VERSIONS_REPO_URL \\
           --packages-metadata-repo-branch $versionsRepoCommitId \\
           $packagesParameter \\
           $params.BUILD_PACKAGES_EXTRA_PARAMETERS \\
"""
        buildInfo.build_packages_finished = true
      }
    }
  }

  if (buildInfo.build_packages_finished) {
    dir('builds/result/packages') {
      File latestBuildDir = new File(pwd(), 'latest')
      buildTimestamp = sh(script: "readlink $latestBuildDir",
        returnStdout: true).trim()
      echo "Build timestamp: $buildTimestamp"
    }

    sh 'ln -s builds/result/packages/latest repository'
    stash name: 'repository_dir', includes: 'repository/'
    utils.archiveAndPrint('repository/')
  }

  dir('logs') {
    sh "ln -s $params.BUILDS_WORKSPACE_DIR build-packages"
  }
  utils.archiveAndPrint('logs/build-packages/*.log', true)
  utils.archiveAndPrint('logs/build-packages/mock_build/*/*/*.log', true)

  if (!buildInfo.build_packages_finished) {
    error('Packages build failed')
  }
}

def buildIso() {
  if (!buildInfo.build_packages_finished) {
    error('Skipping ISO build because packages build failed')
  }

  buildInfo.build_iso_finished = false

  if (triggeredRepoName) {
    utils.setGithubStatus(
      triggeredRepoName, 'Building ISO', 'PENDING')
  }

  // Convert timestamp from format YYYY-MM-DDThh:mm:ss.ssssss
  // to YYYYMMDDThhmmss
  String ISO_VERSION = buildTimestamp
  ISO_VERSION = ISO_VERSION.replaceAll(/-/, '')
  ISO_VERSION = ISO_VERSION.replaceAll(/:/, '')
  ISO_VERSION = ISO_VERSION.replaceFirst(/[.].*/, '')

  deleteDir()
  utils.checkoutRepo('builds', gitRepos)

  unstash 'repository_dir'

  dir('builds') {
    // Tell mock and pungi to use different CentOS and EPEL mirrors/repos.
    // This could be used to:
    // - speedup the chroot installation
    // - use a different version of CentOS
    // - workaround any issue with CentOS official mirrors
    if (params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL) {
      utils.replaceInFile(params.MOCK_CONFIG_FILE,
                          params.MAIN_CENTOS_REPO_RELEASE_URL,
                          params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL)
      utils.replaceInFile(params.BUILDS_CONFIG_FILE,
                          params.MAIN_CENTOS_REPO_RELEASE_URL,
                          params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL)
    }
    if (params.EPEL_ALTERNATE_MIRROR_RELEASE_URL) {
      // The mock configuration file currently has a mirror list URL for
      // EPEL, which can't be replaced in the same manner as the others.
      utils.replaceInFile(params.BUILDS_CONFIG_FILE,
                          params.MAIN_EPEL_REPO_RELEASE_URL,
                          params.EPEL_ALTERNATE_MIRROR_RELEASE_URL)
    }

    lock(resource: "build-iso_workspace_$env.NODE_NAME") {
      echo 'Building ISO'
      catchError {
        sh """\
python host_os.py \\
       build-iso \\
           --packages-dir ../repository \\
           --iso-version $ISO_VERSION \\
           $params.BUILD_ISO_EXTRA_PARAMETERS \\
"""
        buildInfo.build_iso_finished = true
      }
    }
  }

  if (buildInfo.build_iso_finished) {
    sh 'ln -s builds/result/iso/latest iso'
    stash name: 'iso_dir', includes: 'iso/'
    utils.archiveAndPrint('iso/')
  }

  dir('logs') {
    sh 'ln -s ../builds/workspace build-iso'
  }
  utils.archiveAndPrint('logs/build-iso/*.log', true)

  if (!buildInfo.build_iso_finished) {
    error('ISO build failed')
  }
}

def uploadArtifacts() {
  if (triggeredRepoName) {
    utils.setGithubStatus(
      triggeredRepoName, 'Uploading artifacts', 'PENDING')
  }

  deleteDir()
  if (buildInfo.build_packages_finished) {
    unstash 'repository_dir'

    dir(constants.BUILD_INFORMATION_DIR) {
      sh 'mv ../repository/*.json ./'
    }
  }
  if (buildInfo.build_iso_finished) {
    unstash 'iso_dir'
  }

  String BUILDS_DIR_NAME
  String BUILDS_DIR_RSYNC_URL
  String BUILD_DIR_HTTP_URL
  String BUILD_DIR_RSYNC_URL

  if (buildTimestamp) {
    String HTTP_URL_PREFIX = "http://$params.UPLOAD_SERVER_HOST_NAME"
    String RSYNC_URL_PREFIX =
      "$params.UPLOAD_SERVER_USER_NAME@$params.UPLOAD_SERVER_HOST_NAME:"
    BUILDS_DIR_NAME = (
      params.UPLOAD_SERVER_BUILDS_DIR_PATH.tokenize('/').last())
    String BUILD_DIR_PATH =
      "$params.UPLOAD_SERVER_BUILDS_DIR_PATH/$buildTimestamp"
    BUILDS_DIR_RSYNC_URL =
      "${RSYNC_URL_PREFIX}$params.UPLOAD_SERVER_BUILDS_DIR_PATH"
    BUILD_DIR_HTTP_URL = "${HTTP_URL_PREFIX}$BUILD_DIR_PATH"
    BUILD_DIR_RSYNC_URL = "${RSYNC_URL_PREFIX}$BUILD_DIR_PATH"

    buildInfo.repository_file_url = "$BUILD_DIR_HTTP_URL/hostos.repo"
  }

  String jsonString = JsonOutput.prettyPrint(JsonOutput.toJson(buildInfo))
  echo "Writing build status file:\n" + jsonString
  dir(constants.BUILD_INFORMATION_DIR) {
    writeFile file: 'pipeline.json', text: jsonString
    utils.archiveAndPrint('pipeline.json')
  }
  if (!buildTimestamp) {
    error('Aborting upload, no timestamp to create the remote directory name')
  }

  String repositoryConfiguration = """\
[hostos]
name=hostos
baseurl=$BUILD_DIR_HTTP_URL/repository
enabled=1
priority=1
gpgcheck=0
"""
  echo "Writing yum repository configuration file:\n" + repositoryConfiguration
  writeFile file: 'hostos.repo', text: repositoryConfiguration
  utils.archiveAndPrint('hostos.repo')

  echo 'Creating remote build directory hierarchy'
  sh "mkdir -p $BUILDS_DIR_NAME/$buildTimestamp"
  utils.rsyncUpload("--recursive $BUILDS_DIR_NAME/", BUILDS_DIR_RSYNC_URL)

  echo 'Uploading artifacts'
  // The --ignore-existing prevents a build from being overwritten if some
  // problem occurs and the build is manually replayed, for example
  utils.rsyncUpload('--ignore-existing --recursive ' +
      constants.BUILD_INFORMATION_DIR, BUILD_DIR_RSYNC_URL)
  if (buildInfo.build_packages_finished) {
    utils.rsyncUpload('--ignore-existing --recursive repository',
                      BUILD_DIR_RSYNC_URL)
    utils.rsyncUpload('hostos.repo', BUILD_DIR_RSYNC_URL)
  }
  if (buildInfo.build_iso_finished) {
    utils.rsyncUpload('--ignore-existing --recursive iso', BUILD_DIR_RSYNC_URL)
  }
}

return this
