#!/usr/bin/groovy

import java.util.regex.Pattern

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def openShiftTemplate = config.openShiftTemplate
    def originalImageName = config.originalImageName
    def newImageName = config.newImageName
    def deploymentName = config.githubRepo
    def providerLabel = config.providerLabel ?: 'fabric8'
    def project = config.githubProject

    def flow = new io.fabric8.Fabric8Commands()
    def utils = new io.fabric8.Utils()
    def openShiftProject = config.openShiftProject + '-' + utils.getRepoName()

    if (!flow.isAuthorCollaborator("", project)){
        currentBuild.result = 'ABORTED'
        error 'Change author is not a collaborator on the project, aborting build until we support the [test] comment'
    }
    def yaml = flow.getUrlAsString(openShiftTemplate)
    def originalImage = "- image: ${originalImageName}:(.*)"
    def newImage = "- image: ${newImageName}"
    def compiledYaml = Pattern.compile(originalImage).matcher(yaml).replaceFirst(newImage)

    if (!compiledYaml.contains(newImage)){
        error "original image ${originalImage} not replaced with ${newImage} in yaml: \n ${compiledYaml}"
    }
    // cant use writeFile as we are facing following error
    // Scripts not permitted to use staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods
    sh "echo '${compiledYaml}' > snapshot.yml"

    container('clients') {
        try {
            sh "oc get project ${openShiftProject} | grep Active"
        } catch (err) {
            echo "${err}"
            sh "oc new-project ${openShiftProject}"
        }

        sh "oc process -n ${openShiftProject} -f ./snapshot.yml | oc apply -n ${openShiftProject} -f -"

        sleep 10
        // ok bad bad but there's a delay between DC's being applied and new pods being started.  lets find a better way to do this looking at teh new DC perhaps?

        waitUntil {
            // wait until the pods are running has been deleted
            try {
                sh "oc get pod -l app=${deploymentName},provider=${providerLabel} -n ${openShiftProject} | grep Running"
                echo "${deploymentName} pod is running"
                return true
            } catch (err) {
                echo "waiting for ${deploymentName} to be ready..."
                return false
            }
        }
        return sh(script: "oc get route ${deploymentName} -o jsonpath=\"{.spec.host}\" -n ${openShiftProject}", returnStdout: true).toString().trim()
    }
}
