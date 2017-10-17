/*
 * Copyright 2016 srhebbar.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jenkins.plugins.office365connector;


import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import jenkins.plugins.office365connector.model.Card;
import jenkins.plugins.office365connector.model.Fact;
import jenkins.plugins.office365connector.model.Macro;
import jenkins.plugins.office365connector.model.PotentialAction;
import jenkins.plugins.office365connector.model.Section;
import jenkins.plugins.office365connector.workflow.StepParameters;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.metadata.ContributorMetadataAction;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.api.mixin.ChangeRequestSCMHead;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

/**
 * @author srhebbar
 */
public final class Office365ConnectorWebhookNotifier {

    private static final Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.IDENTITY).create();

    private final FactsBuilder factsBuilder = new FactsBuilder();

    private final Run run;
    private final TaskListener listener;

    public Office365ConnectorWebhookNotifier(Run run, TaskListener listener) {
        this.run = run;
        this.listener = listener;
    }

    public void sendBuildStartedNotification(boolean isFromPreBuild) {
        Card card = null;

        boolean isBuild = run instanceof AbstractBuild<?, ?>;
        if ((isBuild && isFromPreBuild) || (!isBuild && !isFromPreBuild)) {
            card = createJobStartedCard();
        }

        if (card == null) {
            listener.getLogger().println(String.format("Build started card not generated."));
            return;
        }

        WebhookJobProperty property = (WebhookJobProperty) run.getParent().getProperty(WebhookJobProperty.class);
        if (property == null) {
            //     listener.getLogger().println(String.format("No webhooks to notify"));
            return;
        }

        for (Webhook webhook : property.getWebhooks()) {
            if (isAtLeastOneRuleMatched(webhook)) {
                if (webhook.isStartNotification()) {
                    executeWorker(webhook, card);
                }
            }
        }
    }

    public void sendBuildCompleteNotification() {
        Card card = createJobCompletedCard();

        WebhookJobProperty property = (WebhookJobProperty) run.getParent().getProperty(WebhookJobProperty.class);
        if (property == null) {
            //           listener.getLogger().println(String.format("No webhooks to notify"));
            return;
        }

        for (Webhook webhook : property.getWebhooks()) {
            if (isStatusMatched(webhook) && isAtLeastOneRuleMatched(webhook)) {
                executeWorker(webhook, card);
            }
        }
    }

    public void sendBuildMessage(StepParameters stepParameters) {
        Card card;
        if (StringUtils.isNotBlank(stepParameters.getMessage())) {
            card = createBuildMessageCard(stepParameters);
        } else if (StringUtils.equalsIgnoreCase(stepParameters.getStatus(), "started")) {
            card = createJobStartedCard();
        } else {
            card = createJobCompletedCard();
        }

        WebhookJobProperty property = (WebhookJobProperty) run.getParent().getProperty(WebhookJobProperty.class);
        if (property == null) {
            Webhook webhook = new Webhook(stepParameters.getWebhookUrl());
            executeWorker(webhook, card);
            return;
        }

        for (Webhook webhook : property.getWebhooks()) {
            executeWorker(webhook, card);
        }
    }

    private Card createJobStartedCard() {

        factsBuilder.addStatusStarted();
        factsBuilder.addStartTime(run);
        factsBuilder.addRemarks(run.getCauses());
        addScmDetails();

        String jobName = run.getParent().getDisplayName();
        String activityTitle = "Update from " + jobName + ".";
        String activitySubtitle = "Latest status of build #" + run.getNumber();
        Section section = new Section(activityTitle, activitySubtitle, factsBuilder.collect());

        List<Section> sectionList = new ArrayList<>();
        sectionList.add(section);

        String summary = jobName + ": Build #" + run.getNumber() + " Started";
        Card card = new Card(summary, sectionList);
        addPotentialAction(card);

        return card;
    }

    private Card createJobCompletedCard() {
        String jobName = run.getParent().getDisplayName();
        String summary = jobName + ": Build #" + run.getNumber();

        Fact statusFact = FactsBuilder.buildStatus();
        factsBuilder.addFact(statusFact);
        factsBuilder.addStartTime(run);

        // Result is only set to a worse status in pipeline
        Result result = run.getResult() == null ? Result.SUCCESS : run.getResult();
        if (result != null) {

            factsBuilder.addCompletionTime(run);
            factsBuilder.addTests(run);

            String status;
            Run previousBuild = run.getPreviousBuild();
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            Run rt = run.getPreviousNotFailedBuild();
            Run failingSinceRun;
            if (rt != null) {
                failingSinceRun = rt.getNextBuild();
            } else {
                failingSinceRun = run.getParent().getFirstBuild();
            }

            if (result == Result.SUCCESS && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)) {
                status = "Back to Normal";
                summary += " Back to Normal";

                if (failingSinceRun != null) {
                    long duration = run.getDuration() == 0L
                            ? System.currentTimeMillis() - run.getStartTimeInMillis()
                            : run.getDuration();
                    long currentBuildCompletionTime = run.getStartTimeInMillis() + duration;
                    factsBuilder.addBackToNormalTime(currentBuildCompletionTime - failingSinceRun.getStartTimeInMillis());
                }
            } else if (result == Result.FAILURE && failingSinceRun != null) {
                if (previousResult == Result.FAILURE) {
                    status = "Repeated Failure";
                    summary += " Repeated Failure";

                    factsBuilder.addFailingSinceBuild(failingSinceRun.number);
                    factsBuilder.addFailingSinceTime(failingSinceRun.getStartTimeInMillis() + failingSinceRun.getDuration());
                } else {
                    status = "Build Failed";
                    summary += " Failed";
                }
            } else if (result == Result.ABORTED) {
                status = "Build Aborted";
                summary += " Aborted";
            } else if (result == Result.UNSTABLE) {
                status = "Build Unstable";
                summary += " Unstable";
            } else if (result == Result.SUCCESS) {
                status = "Build Success";
                summary += " Success";
            } else if (result == Result.NOT_BUILT) {
                status = "Not Built";
                summary += " Not Built";
            } else {
                status = result.toString();
                summary += " " + status;
            }

            statusFact.setValue(status);
        } else {
            statusFact.setValue(" Completed");
            summary += " Completed";
        }

        factsBuilder.addRemarks(run.getCauses());
        addScmDetails();

        String activityTitle = "Update from " + jobName + ".";
        String activitySubtitle = "Latest status of build #" + run.getNumber();
        Section section = new Section(activityTitle, activitySubtitle, factsBuilder.collect());

        List<Section> sectionList = new ArrayList<>();
        sectionList.add(section);

        Card card = new Card(summary, sectionList);
        if (result == Result.SUCCESS) {
            card.setThemeColor("96CEB4");
        } else if (result == Result.FAILURE) {
            card.setThemeColor("FF6F69");
        } else {
            card.setThemeColor("FFCC5C");
        }
        addPotentialAction(card);

        return card;
    }

    private Card createBuildMessageCard(StepParameters stepParameters) {
        String jobName = run.getParent().getDisplayName();
        if (stepParameters.getStatus() != null) {
            Fact fact = FactsBuilder.buildStatus();
            fact.setValue(stepParameters.getStatus());
            factsBuilder.addFact(fact);
        } else {
            factsBuilder.addStatusRunning();
        }

        String activityTitle = "Message from " + jobName + ", Build #" + run.getNumber() + "";
        Section section = new Section(activityTitle, stepParameters.getMessage(), factsBuilder.collect());

        List<Section> sectionList = new ArrayList<>();
        sectionList.add(section);

        String summary = jobName + ": Build #" + run.getNumber() + " Status";
        Card card = new Card(summary, sectionList);

        if (stepParameters.getColor() != null) {
            card.setThemeColor(stepParameters.getColor());
        }

        addPotentialAction(card);

        return card;
    }

    private void executeWorker(Webhook webhook, Card card) {
        try {
            HttpWorker worker = new HttpWorker(run.getEnvironment(listener).expand(webhook.getUrl()), gson.toJson(card),
                    webhook.getTimeout(), listener.getLogger());
            worker.submit();
        } catch (IOException | InterruptedException | RejectedExecutionException e) {
            listener.getLogger().println(String.format("Failed to notify webhook '%s' - %s: %s", webhook,
                    e.getClass().getName(), e.getMessage()));
        }
    }

    /**
     * Iterates over each macro for passed webhook and checks if at least one template matches to expected value.
     *
     * @param webhook webhook that should be examined
     * @return <code>true</code> if at least one macro has matched, <code>false</code> otherwise
     */
    private boolean isAtLeastOneRuleMatched(Webhook webhook) {
        if (CollectionUtils.isEmpty(webhook.getMacros())) {
            return true;
        } else {
            for (Macro macro : webhook.getMacros()) {
                String evaluated = evaluateMacro(macro.getTemplate());
                if (evaluated.equals(macro.getValue())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Checks if notification should be passed by comparing current status and webhook configuration
     *
     * @param webhook webhook that will be verified
     * @return <code>true</code> if current status matches to webhook configuration
     */
    private boolean isStatusMatched(Webhook webhook) {
        Result result = run.getResult();
        Run previousBuild = run.getPreviousBuild();
        Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;

        return ((result == Result.ABORTED && webhook.isNotifyAborted())
                || (result == Result.FAILURE && previousResult != Result.FAILURE && (webhook.isNotifyFailure()))
                || (result == Result.FAILURE && previousResult == Result.FAILURE && (webhook.isNotifyRepeatedFailure()))
                || (result == Result.NOT_BUILT && webhook.isNotifyNotBuilt())
                || (result == Result.SUCCESS && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE) && webhook.isNotifyBackToNormal())
                || (result == Result.SUCCESS && webhook.isNotifySuccess())
                || (result == Result.UNSTABLE && webhook.isNotifyUnstable()));
    }

    private void addScmDetails() {
        try {
            if (run instanceof AbstractBuild) {
                AbstractBuild build = (AbstractBuild) run;
                factsBuilder.addCulprits(run.getResult(), build.getCulprits());

                ChangeLogSet changeSet = build.getChangeSet();
                List<ChangeLogSet.Entry> entries = new LinkedList<>();
                Set<ChangeLogSet.AffectedFile> files = new HashSet<>();
                for (Object o : changeSet.getItems()) {
                    ChangeLogSet.Entry entry = (ChangeLogSet.Entry) o;
                    entries.add(entry);
                    files.addAll(getAffectedFiles(entry));
                }
                if (!entries.isEmpty()) {
                    Set<User> authors = new HashSet<>();
                    for (ChangeLogSet.Entry entry : entries) {
                        authors.add(entry.getAuthor());
                    }

                    factsBuilder.addDevelopers(authors);
                    factsBuilder.addNumberOfFilesChanged(files);
                }
            } else {
                try {
                    // newer Jenkins uses jenkins.scm.RunWithSCM interface so such casting is not needed
                    Method getCulprits = run.getClass().getMethod("getCulprits");
                    @SuppressWarnings("unchecked")
                    Set<User> users = (Set<User>) getCulprits.invoke(run);
                    factsBuilder.addCulprits(run.getResult(), users);

                    Method getChangeSets = run.getClass().getMethod("getChangeSets");
                    @SuppressWarnings("unchecked")
                    List<ChangeLogSet<ChangeLogSet.Entry>> sets = (List<ChangeLogSet<ChangeLogSet.Entry>>) getChangeSets.invoke(run);
                    Set<User> authors = new HashSet<>();
                    Set<ChangeLogSet.AffectedFile> files = new HashSet<>();
                    if (Iterables.all(sets, Predicates.instanceOf(ChangeLogSet.class))) {
                        for (ChangeLogSet<ChangeLogSet.Entry> set : sets) {
                            for (ChangeLogSet.Entry entry : set) {
                                authors.add(entry.getAuthor());
                                files.addAll(getAffectedFiles(entry));
                            }
                        }
                    }

                    factsBuilder.addDevelopers(authors);
                    factsBuilder.addNumberOfFilesChanged(files);

                } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                    e.printStackTrace(listener.error(String.format("Exception getting changesets for %s: %s", run, e)));
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            e.printStackTrace(listener.error(String.format("Unable to cast run to abstract build. %s", e)));
        }
    }

    private Collection<? extends ChangeLogSet.AffectedFile> getAffectedFiles(ChangeLogSet.Entry entry) {
        try {
            return entry.getAffectedFiles();
        } catch (UnsupportedOperationException e) {
            listener.getLogger().println(e.getMessage());
            return Collections.emptyList();
        }
    }

    private void addPotentialAction(Card card) {
        String urlString = DisplayURLProvider.get().getRunURL(run);
        String build = Messages.Office365ConnectorWebhookNotifier_BuildPronoun();
        String viewHeader = Messages.Office365ConnectorWebhookNotifier_ViewHeader(build);
        PotentialAction viewBuildPotentialAction = new PotentialAction(viewHeader, urlString);
        List<PotentialAction> paList = new ArrayList<>();
        paList.add(viewBuildPotentialAction);
        card.setPotentialAction(paList);
        pullRequestActionable(paList);
    }

    private String evaluateMacro(String template) {
        try {
            File workspace = run.getRootDir();
            return TokenMacro.expandAll(run, new FilePath(workspace), listener, template);
        } catch (InterruptedException | IOException | MacroEvaluationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private void pullRequestActionable(List<PotentialAction> paList) {
        Job job = run.getParent();
        SCMHead head = SCMHead.HeadByItem.findHead(job);
        if (head instanceof ChangeRequestSCMHead) {
            String pronoun = StringUtils.defaultIfBlank(
                    head.getPronoun(),
                    Messages.Office365ConnectorWebhookNotifier_ChangeRequestPronoun()
            );
            String viewHeader = Messages.Office365ConnectorWebhookNotifier_ViewHeader(pronoun);
            String titleHeader = Messages.Office365ConnectorWebhookNotifier_TitleHeader(pronoun);
            String authorHeader = Messages.Office365ConnectorWebhookNotifier_AuthorHeader(pronoun);

            ObjectMetadataAction oma = job.getAction(ObjectMetadataAction.class);
            if (oma != null) {
                String urlString = oma.getObjectUrl();
                PotentialAction viewPRPotentialAction = new PotentialAction(viewHeader, urlString);
                paList.add(viewPRPotentialAction);
                factsBuilder.addFact(titleHeader, oma.getObjectDisplayName());
            }
            ContributorMetadataAction cma = job.getAction(ContributorMetadataAction.class);
            if (cma != null) {
                String contributor = cma.getContributor();
                String contributorDisplayName = cma.getContributorDisplayName();
                String author = StringUtils.defaultIfBlank(cma.getContributor(), cma.getContributorDisplayName());
                if (StringUtils.isNotBlank(contributor) && StringUtils.isNotBlank(contributorDisplayName)) {
                    author = String.format("%s (%s)", cma.getContributor(), cma.getContributorDisplayName());
                }
                factsBuilder.addFact(authorHeader, author);
            }
        }
    }
}
