// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.config;

import static com.google.inject.Scopes.SINGLETON;

import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.lifecycle.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.reviewdb.AccountGroup;
import com.google.gerrit.reviewdb.AuthType;
import com.google.gerrit.reviewdb.Project;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.FileTypeRegistry;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.GerritPersonIdentProvider;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.MimeUtilFileTypeRegistry;
import com.google.gerrit.server.ReplicationUser;
import com.google.gerrit.server.account.AccountByEmailCacheImpl;
import com.google.gerrit.server.account.AccountCacheImpl;
import com.google.gerrit.server.account.AccountInfoCacheFactory;
import com.google.gerrit.server.account.DefaultRealm;
import com.google.gerrit.server.account.EmailExpander;
import com.google.gerrit.server.account.GroupCacheImpl;
import com.google.gerrit.server.account.Realm;
import com.google.gerrit.server.auth.ldap.LdapModule;
import com.google.gerrit.server.cache.CachePool;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.events.EventFactory;
import com.google.gerrit.server.git.ChangeMergeQueue;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.git.PushAllProjectsOp;
import com.google.gerrit.server.git.PushReplication;
import com.google.gerrit.server.git.ReloadSubmitQueueOp;
import com.google.gerrit.server.git.ReplicationQueue;
import com.google.gerrit.server.git.TransferConfig;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.mail.EmailSender;
import com.google.gerrit.server.mail.FromAddressGenerator;
import com.google.gerrit.server.mail.FromAddressGeneratorProvider;
import com.google.gerrit.server.mail.SmtpEmailSender;
import com.google.gerrit.server.patch.PatchListCacheImpl;
import com.google.gerrit.server.patch.PatchSetInfoFactory;
import com.google.gerrit.server.project.AccessControlModule;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.ProjectCacheImpl;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.tools.ToolsCatalog;
import com.google.gerrit.server.util.IdGenerator;
import com.google.gerrit.server.workflow.FunctionState;
import com.google.inject.Inject;
import com.google.inject.TypeLiteral;

import org.apache.velocity.app.Velocity;
import org.apache.velocity.runtime.RuntimeConstants;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;

import java.util.Properties;
import java.util.Set;


/** Starts global state with standard dependencies. */
public class GerritGlobalModule extends FactoryModule {
  private final AuthType loginType;

  public static class VelocityLifecycle implements LifecycleListener {
    private final SitePaths site;

    @Inject
    VelocityLifecycle(final SitePaths site) {
      this.site = site;
    }

    @Override
    public void start() {
      String rl = "resource.loader";
      String pkg = "org.apache.velocity.runtime.resource.loader";
      Properties p = new Properties();

      p.setProperty(rl, "file, class");
      p.setProperty("file." + rl + ".class", pkg + ".FileResourceLoader");
      p.setProperty("file." + rl + ".path", site.mail_dir.getAbsolutePath());
      p.setProperty("class." + rl + ".class", pkg + ".ClasspathResourceLoader");
      p.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
              "org.apache.velocity.runtime.log.SimpleLog4JLogSystem" );
      p.setProperty("runtime.log.logsystem.log4j.category", "velocity");

      try {
        Velocity.init(p);
      } catch(Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void stop() {
    }
  }

  @Inject
  GerritGlobalModule(final AuthConfig authConfig,
      @GerritServerConfig final Config config) {
    loginType = authConfig.getAuthType();
  }

  @Override
  protected void configure() {
    switch (loginType) {
      case HTTP_LDAP:
      case LDAP:
      case LDAP_BIND:
        install(new LdapModule());
        break;

      default:
        bind(Realm.class).to(DefaultRealm.class);
        break;
    }

    bind(Project.NameKey.class).annotatedWith(WildProjectName.class)
        .toProvider(WildProjectNameProvider.class).in(SINGLETON);
    bind(ApprovalTypes.class).toProvider(ApprovalTypesProvider.class).in(
        SINGLETON);
    bind(EmailExpander.class).toProvider(EmailExpanderProvider.class).in(
        SINGLETON);
    bind(AnonymousUser.class);

    bind(PersonIdent.class).annotatedWith(GerritPersonIdent.class).toProvider(
        GerritPersonIdentProvider.class);

    bind(IdGenerator.class);
    bind(CachePool.class);
    install(AccountByEmailCacheImpl.module());
    install(AccountCacheImpl.module());
    install(GroupCacheImpl.module());
    install(PatchListCacheImpl.module());
    install(ProjectCacheImpl.module());
    install(new AccessControlModule());

    factory(AccountInfoCacheFactory.Factory.class);
    factory(ProjectState.Factory.class);

    bind(GitRepositoryManager.class).to(LocalDiskRepositoryManager.class);
    bind(FileTypeRegistry.class).to(MimeUtilFileTypeRegistry.class);
    bind(WorkQueue.class);
    bind(ToolsCatalog.class);
    bind(EventFactory.class);
    bind(TransferConfig.class);

    bind(ReplicationQueue.class).to(PushReplication.class).in(SINGLETON);
    factory(PushAllProjectsOp.Factory.class);

    bind(MergeQueue.class).to(ChangeMergeQueue.class).in(SINGLETON);
    factory(ReloadSubmitQueueOp.Factory.class);

    bind(FromAddressGenerator.class).toProvider(
        FromAddressGeneratorProvider.class).in(SINGLETON);
    bind(EmailSender.class).to(SmtpEmailSender.class).in(SINGLETON);

    bind(PatchSetInfoFactory.class);
    bind(IdentifiedUser.GenericFactory.class).in(SINGLETON);
    bind(ChangeControl.GenericFactory.class);
    bind(ProjectControl.GenericFactory.class);
    factory(FunctionState.Factory.class);
    factory(ReplicationUser.Factory.class);

    install(new LifecycleModule() {
      @Override
      protected void configure() {
        listener().to(LocalDiskRepositoryManager.Lifecycle.class);
        listener().to(CachePool.Lifecycle.class);
        listener().to(WorkQueue.Lifecycle.class);
        listener().to(VelocityLifecycle.class);
      }
    });
  }
}
