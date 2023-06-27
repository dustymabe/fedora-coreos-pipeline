import org.yaml.snakeyaml.Yaml;

node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    libcloud = load("libcloud.groovy")
}

// Base URL through which to download artifacts
BUILDS_BASE_HTTP_URL = "https://builds.coreos.fedoraproject.org/prod/streams"

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to build'),
      string(name: 'VERSION',
             description: 'Override default versioning mechanism',
             defaultValue: '',
             trim: true),
      string(name: 'ADDITIONAL_ARCHES',
             description: "Override additional architectures (space-separated). " +
                          "Use 'none' to only build for x86_64. " +
                          "Supported: ${pipeutils.get_supported_additional_arches().join(' ')}",
             defaultValue: "",
             trim: true),
      booleanParam(name: 'FORCE',
                   defaultValue: false,
                   description: 'Whether to force a rebuild'),
      booleanParam(name: 'EARLY_ARCH_JOBS',
                   defaultValue: true,
                   description: "Fork off the multi-arch jobs before all tests have run"),
      booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE',
                   defaultValue: false,
                   description: "Don't error out if upgrade tests fail (temporary)"),
      booleanParam(name: 'CLOUD_REPLICATION',
                   defaultValue: false,
                   description: 'Force cloud image replication for non-production'),
      string(name: 'COREOS_ASSEMBLER_IMAGE',
             description: 'Override coreos-assembler image to use',
             defaultValue: "",
             trim: true),
      booleanParam(name: 'KOLA_RUN_SLEEP',
                   defaultValue: false,
                   description: 'Wait forever at kola tests stage. Implies NO_UPLOAD'),
      booleanParam(name: 'NO_UPLOAD',
                   defaultValue: false,
                   description: 'Do not upload results to S3; for debugging purposes.'),
      booleanParam(name: 'WAIT_FOR_RELEASE_JOB',
                   defaultValue: false,
                   description: 'Wait for the release job and propagate errors.'),
    ] + pipeutils.add_hotfix_parameters_if_supported()),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.STREAM}][x86_64]"

// Reload pipecfg if a hotfix build was provided. The reason we do this here
// instead of loading the right one upfront is so that we don't modify the
// parameter definitions above and their default values.
if (params.PIPECFG_HOTFIX_REPO || params.PIPECFG_HOTFIX_REF) {
    node {
        pipecfg = pipeutils.load_pipecfg(params.PIPECFG_HOTFIX_REPO, params.PIPECFG_HOTFIX_REF)
        build_description = "[${params.STREAM}-${pipecfg.hotfix.name}][x86_64]"
    }
}

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = cosa_img ?: pipeutils.get_cosa_img(pipecfg, params.STREAM)
def additional_arches = []
if (params.ADDITIONAL_ARCHES != "none") {
    additional_arches = params.ADDITIONAL_ARCHES.split()
    additional_arches = additional_arches ?: pipeutils.get_additional_arches(pipecfg, params.STREAM)
}

def stream_info = pipecfg.streams[params.STREAM]

// If we are a mechanical stream then we can pin packages but we
// don't maintain complete lockfiles so we can't build in strict mode.
def strict_build_param = stream_info.type == "mechanical" ? "" : "--strict"

// Note the supermin VM just uses 2G. The really hungry part is xz, which
// without lots of memory takes lots of time. For now we just hardcode these
// here; we can look into making them configurable through the template if
// developers really need to tweak them.
// XXX bump an extra 2G (to 10.5) because of an error we are seeing in
// testiso: https://github.com/coreos/fedora-coreos-tracker/issues/1339
def cosa_memory_request_mb = 10.5 * 1024 as Integer

// Now that we've established the memory constraint based on xz above, derive
// kola parallelism from that. We leave 512M for overhead and VMs are at most
// 1.5G each (in general; root reprovisioning tests require 4G which is partly
// why we run them separately).
// XXX: https://github.com/coreos/coreos-assembler/issues/3118 will make this
// cleaner
def ncpus = ((cosa_memory_request_mb - 512) / 1536) as Integer

echo "Waiting for build-${params.STREAM} lock"
currentBuild.description = "${build_description} Waiting"

// declare these early so we can use them in `finally` block
def newBuildID, basearch

// matches between build/build-arch job
def timeout_mins = 240

if (params.WAIT_FOR_RELEASE_JOB) {
    // Waiting for the release job effectively means waiting for all the build-
    // arch jobs we trigger to finish. While we do overlap in execution (by
    // a lot when EARLY_ARCH_JOBS is set), let's just simplify and add its
    // timeout value to ours to account for this. Add 30 minutes more for the
    // release job itself.
    timeout_mins += timeout_mins + 30
}

lock(resource: "build-${params.STREAM}") {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_img,
            serviceAccount: "jenkins") {
    timeout(time: timeout_mins, unit: 'MINUTES') {
    try {

        basearch = shwrapCapture("cosa basearch")
        currentBuild.description = "${build_description} Running"

        // this is defined IFF we *should* and we *can* upload to S3
        def s3_stream_dir

        if (pipecfg.s3 && pipeutils.AWSBuildUploadCredentialExists()) {
            s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
        }

        // Now, determine if we should do any uploads to remote s3 buckets or clouds
        // Don't upload if the user told us not to or we're debugging with KOLA_RUN_SLEEP
        def uploading = false
        if (s3_stream_dir && (!params.NO_UPLOAD || params.KOLA_RUN_SLEEP)) {
            uploading = true
        }

        // add any additional root CA cert before we do anything that fetches
        pipeutils.addOptionalRootCA()

        def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
        def src_config_commit = shwrapCapture("git ls-remote ${pipecfg.source_config.url} refs/heads/${ref} | cut -d \$'\t' -f 1")

        newBuildID = params.VERSION

        stage('Fetch Metadata') {
            withCredentials([file(variable: 'AWS_CONFIG_FILE',
                                  credentialsId: 'aws-build-upload-config')]) {
                def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
                shwrap("""
                cosa init --branch ${ref} ${variant} ${pipecfg.source_config.url}
                cosa buildfetch --build=${params.VERSION} \
                    --arch=${basearch} --url=s3://${s3_stream_dir}/builds
                """)
            }
        }


        stage('Cloud Tests') {
            pipeutils.run_cloud_tests(pipecfg, params.STREAM, newBuildID,
                                      cosa_img, basearch, src_config_commit)
        }
        if (pipecfg.misc?.run_extended_upgrade_test_fcos) {
            stage('Upgrade Tests') {
                    pipeutils.run_fcos_upgrade_tests(pipecfg, params.STREAM, newBuildID,
                                                     cosa_img, basearch, src_config_commit)
            }
        }

        // If we didn't do an early archive and start multi-arch
        // jobs let's go ahead and do those pieces now
        run_multiarch_jobs(additional_arches, src_config_commit, newBuildID, cosa_img, false)
        currentBuild.result = 'SUCCESS'

} catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
} finally {
    def color
    def stream = params.STREAM
    if (pipecfg.hotfix) {
        stream += "-${pipecfg.hotfix.name}"
    }
    def message = "TESTS [${stream}][${basearch}] <${env.BUILD_URL}|${env.BUILD_NUMBER}>"

    if (currentBuild.result == 'SUCCESS') {
        if (!newBuildID) {
            // SUCCESS, but no new builds? Must've been a no-op
            return
        }
        message = ":sparkles: ${message}"
    } else if (currentBuild.result == 'UNSTABLE') {
        message = ":warning: ${message}"
    } else {
        message = ":fire: ${message}"
    }

    if (newBuildID) {
        message = "${message} (${newBuildID})"
    }

    echo message
    pipeutils.trySlackSend(message: message)
    pipeutils.tryWithMessagingCredentials() {
        shwrap("""
        /usr/lib/coreos-assembler/fedmsg-broadcast --fedmsg-conf=\${FEDORA_MESSAGING_CONF} \
            build.state.change --build ${newBuildID} --basearch ${basearch} --stream ${params.STREAM} \
            --build-dir ${BUILDS_BASE_HTTP_URL}/${params.STREAM}/builds/${newBuildID}/${basearch} \
            --state FINISHED --result ${currentBuild.result}
        """)
    }
}}}} // finally, cosaPod, timeout, and locks finish here

def run_multiarch_jobs(arches, src_commit, version, cosa_img, wait) {
    stage('Fork Multi-Arch Builds') {
        parallel arches.collectEntries{arch -> [arch, {
            // We pass in FORCE=true here since if we got this far we know
            // we want to do a build even if the code tells us that there
            // are no apparent changes since the previous commit.
            build job: 'build-arch-tests', wait: wait, parameters: [
                booleanParam(name: 'FORCE', value: true),
                booleanParam(name: 'ALLOW_KOLA_UPGRADE_FAILURE', value: params.ALLOW_KOLA_UPGRADE_FAILURE),
                string(name: 'SRC_CONFIG_COMMIT', value: src_commit),
                string(name: 'COREOS_ASSEMBLER_IMAGE', value: cosa_img),
                string(name: 'STREAM', value: params.STREAM),
                string(name: 'VERSION', value: version),
                string(name: 'ARCH', value: arch),
                string(name: 'PIPECFG_HOTFIX_REPO', value: params.PIPECFG_HOTFIX_REPO),
                string(name: 'PIPECFG_HOTFIX_REF', value: params.PIPECFG_HOTFIX_REF)
            ]
        }]}
    }
}
