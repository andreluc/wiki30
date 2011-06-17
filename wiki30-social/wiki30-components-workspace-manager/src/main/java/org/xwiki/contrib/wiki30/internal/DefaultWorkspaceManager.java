/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.wiki30.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.xwiki.bridge.event.WikiDeletedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Requirement;
import org.xwiki.component.logging.AbstractLogEnabled;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.context.Execution;
import org.xwiki.contrib.wiki30.Workspace;
import org.xwiki.contrib.wiki30.WorkspaceManager;
import org.xwiki.contrib.wiki30.WorkspaceManagerException;
import org.xwiki.contrib.wiki30.WorkspaceManagerMessageTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.ObservationManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseElement;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.PropertyInterface;
import com.xpn.xwiki.plugin.wikimanager.WikiManager;
import com.xpn.xwiki.plugin.wikimanager.WikiManagerMessageTool;
import com.xpn.xwiki.plugin.wikimanager.WikiManagerPluginApi;
import com.xpn.xwiki.plugin.wikimanager.doc.Wiki;
import com.xpn.xwiki.plugin.wikimanager.doc.XWikiServer;
import com.xpn.xwiki.user.api.XWikiRightService;
import com.xpn.xwiki.web.XWikiMessageTool;

/**
 * Implementation of a <tt>WorkspaceManager</tt> component.
 * 
 * @version $Id:$
 */
@Component
public class DefaultWorkspaceManager extends AbstractLogEnabled implements WorkspaceManager, Initializable
{
    /** Admin right. */
    private static final String RIGHT_ADMIN = "admin";

    /** Wiki preferences page for local wiki (unprefixed and relative to the current wiki). */
    private static final String WIKI_PREFERENCES_LOCAL = "XWiki.XWikiPreferences";

    /** Format string for the wiki preferences page of a certain wiki (absolute reference). */
    private static final String WIKI_PREFERENCES_PREFIXED_FORMAT = "%s:" + WIKI_PREFERENCES_LOCAL;

    /** Membership type property name. */
    private static final String WORKSPACE_MEMBERSHIP_TYPE_PROPERTY = "membershipType";

    /** Membership type default value. */
    private static final String WORKSPACE_MEMBERSHIP_TYPE_DEFAULT = "open";

    /** Execution context. */
    @Requirement
    private Execution execution;

    /** Observation manager needed to fire events. */
    @Requirement
    private ObservationManager observationManager;

    /** Internal wiki manager tookit required to overcome the rights checking of the API. */
    private WikiManager wikiManagerInternal;

    /** The message tool to use to generate errors or comments. */
    private XWikiMessageTool messageTool;

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.component.phase.Initializable#initialize()
     */
    public void initialize() throws InitializationException
    {
        XWikiContext deprecatedContext = getXWikiContext();

        /* Should be ok if we initialize and cache (both) message tools with the current context. */

        WikiManagerMessageTool wikiManagerMessageTool = WikiManagerMessageTool.getDefault(deprecatedContext);
        this.wikiManagerInternal = new WikiManager(wikiManagerMessageTool);

        this.messageTool = new WorkspaceManagerMessageTool(deprecatedContext);
    }

    /**
     * Note: This is a method instead of a cached field because the wiki manager plugin API is initialized, at every
     * usage, with the current context. Caching the plugin API instance would mean caching the context and that caused
     * problems.
     * 
     * @return Wrapped wiki manager plugin.
     */
    private WikiManagerPluginApi getWikiManager()
    {
        XWikiContext deprecatedContext = getXWikiContext();

        WikiManagerPluginApi wikiManager =
            (WikiManagerPluginApi) deprecatedContext.getWiki().getPluginApi("wikimanager", deprecatedContext);

        return wikiManager;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.contrib.wiki30.WorkspaceManager#canCreateWorkspace(java.lang.String, java.lang.String)
     */
    public boolean canCreateWorkspace(String userName, String workspaceName)
    {
        XWikiContext deprecatedContext = getXWikiContext();

        /* If XWiki is not in virtual mode, don`t bother. */
        if (!deprecatedContext.getWiki().isVirtualMode()) {
            return false;
        }

        /* User name input validation. */
        if (userName == null || userName.trim().length() == 0) {
            return false;
        }

        /* Do not allow the guest user. XXX: Shouldn't this be decided by the admin trough rights? */
        if (XWikiRightService.GUEST_USER_FULLNAME.equals(userName)) {
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.contrib.wiki30.WorkspaceManager#canEditWorkspace(java.lang.String, java.lang.String)
     */
    public boolean canEditWorkspace(String userName, String workspaceName)
    {
        XWikiContext deprecatedContext = getXWikiContext();

        try {
            XWikiServer wikiServer = getWikiManager().getWikiDocument(workspaceName);
            String wikiOwner = wikiServer.getOwner();

            XWikiRightService rightService = deprecatedContext.getWiki().getRightService();
            String mainWikiPreferencesDocumentName =
                String.format(WIKI_PREFERENCES_PREFIXED_FORMAT, deprecatedContext.getMainXWiki());

            /* Owner or main wiki admin. */
            return wikiOwner.equals(userName)
                || rightService.hasAccessLevel(RIGHT_ADMIN, userName, mainWikiPreferencesDocumentName,
                    deprecatedContext);
        } catch (Exception e) {
            // TODO: Log me!
            e.printStackTrace();
            // if (getLogger().isErrorEnabled()) {
            // XWikiPluginMessageTool msg = getMessageTool(deprecatedContext);
            // getLogger().error(msg.get(WikiManagerMessageTool.LOG_MANAGERCANEDIT), e);
            // }
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.contrib.wiki30.WorkspaceManager#canDeleteWorkspace(java.lang.String, java.lang.String)
     */
    public boolean canDeleteWorkspace(String userName, String workspaceName)
    {
        XWikiContext deprecatedContext = getXWikiContext();

        try {
            XWikiServer wikiServer = getWikiManager().getWikiDocument(workspaceName);
            String wikiOwner = wikiServer.getOwner();

            XWikiRightService rightService = deprecatedContext.getWiki().getRightService();
            String mainWikiPreferencesDocumentName =
                String.format(WIKI_PREFERENCES_PREFIXED_FORMAT, deprecatedContext.getMainXWiki());

            /* Owner or main wiki admin. */
            return deprecatedContext.getWiki().isVirtualMode()
                && (wikiOwner.equals(userName) || rightService.hasAccessLevel(RIGHT_ADMIN, userName,
                    mainWikiPreferencesDocumentName, deprecatedContext));
        } catch (Exception e) {
            // TODO: Log me!
            e.printStackTrace();
            // if (LOG.isErrorEnabled()) {
            // XWikiPluginMessageTool msg = getMessageTool(context);
            // LOG.error(msg.get(WikiManagerMessageTool.LOG_MANAGERCANDELETE), e);
            // }
            return false;
        }
    }

    /**
     * @return the deprecated xwiki context used to manipulate xwiki objects
     */
    private XWikiContext getXWikiContext()
    {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.contrib.wiki30.WorkspaceManager#createWorkspace(java.lang.String, java.util.Map)
     */
    public XWikiServer createWorkspace(String workspaceName, XWikiServer newWikiXObjectDocument) throws XWikiException
    {
        XWikiContext deprecatedContext = getXWikiContext();

        /* Create new wiki. */
        newWikiXObjectDocument.setWikiName(workspaceName);

        String comment = String.format("Created new workspace '%s'", workspaceName);
        XWikiServer result =
            LocalWikiManager.createNewWikiFromTemplate(newWikiXObjectDocument, "workspacetemplate", true, comment,
                deprecatedContext);

        /*
         * Use the XWiki.XWikiAllGroup of the new wiki and add the owner as a member and the XWiki.XWikiAdminGroup of
         * the new wiki and explicitly add the owner as an admin.
         */
        String workspaceOwner = newWikiXObjectDocument.getOwner();

        String currentWikiName = deprecatedContext.getDatabase();
        try {
            deprecatedContext.setDatabase(workspaceName);

            XWiki wiki = deprecatedContext.getWiki();

            /* Add user as workspace member. */
            String workspaceGroupName = "XWikiAllGroup";
            DocumentReference workspaceGroupReference =
                new DocumentReference(workspaceName, "XWiki", workspaceGroupName);
            XWikiDocument workspaceGroupDocument = wiki.getDocument(workspaceGroupReference, deprecatedContext);

            DocumentReference groupClassReference = wiki.getGroupClass(deprecatedContext).getDocumentReference();
            BaseObject workspaceGroupObject = workspaceGroupDocument.newXObject(groupClassReference, deprecatedContext);
            workspaceGroupObject.setStringValue("member", workspaceOwner);

            wiki.saveDocument(workspaceGroupDocument, comment, deprecatedContext);

            /* Add user as workspace admin. */
            String workspaceAdminGroupName = "XWikiAdminGroup";
            DocumentReference workspaceAdminGroupReference =
                new DocumentReference(workspaceName, "XWiki", workspaceAdminGroupName);
            XWikiDocument workspaceAdminGroupDocument =
                wiki.getDocument(workspaceAdminGroupReference, deprecatedContext);

            BaseObject workspaceAdminGroupObject =
                workspaceAdminGroupDocument.newXObject(groupClassReference, deprecatedContext);
            workspaceAdminGroupObject.setStringValue("member", workspaceOwner);

            wiki.saveDocument(workspaceAdminGroupDocument, comment, deprecatedContext);

            // FIXME: See if we need to update the group service cache.
            // try {
            // XWikiGroupService gservice = getGroupService(context);
            // gservice.addUserToGroup(userName, context.getDatabase(), groupName, context);
            // } catch (Exception e) {
            // LOG.error("Failed to update group service cache", e);
            // }
        } catch (Exception e) {
            getLogger().error("Failed to add owner to workspace group.", e);
            // FIXME: throw new WorkspaceManagerException(message, e);
        } finally {
            deprecatedContext.setDatabase(currentWikiName);
        }

        /* Add workspace marker object. */
        String mainWikiName = deprecatedContext.getMainXWiki();
        DocumentReference workspaceClassReference =
            new DocumentReference(mainWikiName, "WorkspaceManager", "WorkspaceClass");

        XWikiDocument wikiDocument = newWikiXObjectDocument.getDocument();
        BaseObject workspaceObject = wikiDocument.getXObject(workspaceClassReference);
        if (workspaceObject == null) {
            workspaceObject = wikiDocument.newXObject(workspaceClassReference, deprecatedContext);
        }

        /* Make sure the required workspace attributes are set. */
        if (workspaceObject.getStringValue(WORKSPACE_MEMBERSHIP_TYPE_PROPERTY) == null) {
            workspaceObject.setStringValue(WORKSPACE_MEMBERSHIP_TYPE_PROPERTY, WORKSPACE_MEMBERSHIP_TYPE_DEFAULT);
        }

        deprecatedContext.getWiki().saveDocument(wikiDocument, comment, deprecatedContext);

        /* TODO: Launch workspace created event. */

        return result;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.contrib.wiki30.WorkspaceManager#deleteWorkspace(java.lang.String)
     */
    public void deleteWorkspace(String workspaceName) throws WorkspaceManagerException
    {
        Workspace workspace = getWorkspace(workspaceName);
        if (workspace == null) {
            throw new WorkspaceManagerException(String.format("Workspace '%s' does not exist", workspaceName));
        }

        XWikiContext deprecatedContext = getXWikiContext();
        XWiki xwiki = deprecatedContext.getWiki();

        /*
         * Copy/paste from Wiki.delete(boolean deleteDatabase) because it checks internally for admin rights and the
         * current user, even if he is the owner of a wiki, might not have admin rights to the main wiki. If the method
         * is called from the main wiki, an owner might not be allowed to delete his wiki. This way we fix it.
         */
        try {
            xwiki.getStore().deleteWiki(workspaceName, deprecatedContext);
            observationManager.notify(new WikiDeletedEvent(workspaceName), workspaceName, deprecatedContext);
            xwiki.getVirtualWikiList().remove(workspaceName);
        } catch (Exception e) {
            throw new WorkspaceManagerException(
                String.format("Failed to delete wiki '%s' from database", workspaceName), e);
        }

        try {
            xwiki.deleteDocument(workspace.getWikiDescriptor().getDocument(), false, deprecatedContext);
        } catch (Exception e) {
            throw new WorkspaceManagerException(String.format("Failed to delete wiki descriptor for workspace '%s'",
                workspace), e);
        }

    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.contrib.wiki30.WorkspaceManager#editWorkspace(java.lang.String,
     *      com.xpn.xwiki.plugin.wikimanager.doc.XWikiServer)
     */
    public void editWorkspace(String workspaceName, XWikiServer modifiedWikiXObjectDocument)
        throws WorkspaceManagerException
    {
        Workspace workspace = getWorkspace(workspaceName);

        XWikiContext deprecatedContext = getXWikiContext();
        XWiki xwiki = deprecatedContext.getWiki();

        Wiki wikiDocument = workspace.getWikiDocument();
        XWikiDocument coreWikiDocument = wikiDocument.getDocument();

        /*
         * Handle changes in the wiki descriptor.
         */
        DocumentReference xwikiServerClassReference =
            new DocumentReference(deprecatedContext.getMainXWiki(), "XWiki", "XWikiServerClass");

        BaseObject currentWikiObject = coreWikiDocument.getXObject(xwikiServerClassReference);
        BaseObject modifiedWikiObject = modifiedWikiXObjectDocument.getDocument().getXObject(xwikiServerClassReference);

        /* Merge the two. */
        updateObject(modifiedWikiObject, currentWikiObject);

        /*
         * Handle changes in the workspace descriptor.
         */
        DocumentReference workspaceClassReference =
            new DocumentReference(deprecatedContext.getMainXWiki(), "WorkspaceManager", "WorkspaceClass");

        BaseObject currentWorkspaceObject = coreWikiDocument.getXObject(workspaceClassReference);
        BaseObject modifiedWorkspaceObject =
            modifiedWikiXObjectDocument.getDocument().getXObject(workspaceClassReference);

        /* Merge the two. */
        updateObject(modifiedWorkspaceObject, currentWorkspaceObject);

        /*
         * Save the changes.
         */
        try {
            xwiki.saveDocument(coreWikiDocument, "Workspace edited", true, deprecatedContext);
        } catch (XWikiException e) {
            throw new WorkspaceManagerException("Failed to save modifications.", e);
        }
    }

    /**
     * Update the contents of an object using the contents of another. Objects must be of the same class.
     * 
     * @param source object that provides the new contents to update with.
     * @param destination object that is to be updated.
     * @throws WorkspaceManagerException if objects' classes differ.
     */
    private void updateObject(BaseObject source, BaseObject destination) throws WorkspaceManagerException
    {
        if (!source.getXClassReference().equals(destination.getXClassReference())) {
            throw new WorkspaceManagerException(String.format("Objects classes are not equal: %s vs %s", source
                .getXClassReference().toString(), destination.getXClassReference().toString()));
        }

        Iterator<String> itfields = source.getPropertyList().iterator();
        while (itfields.hasNext()) {
            String name = (String) itfields.next();
            destination.safeput(name, (PropertyInterface) ((BaseElement) source.safeget(name)).clone());
        }
    }

    public Workspace getWorkspace(String workspaceId) throws WorkspaceManagerException
    {
        XWikiContext deprecatedContext = getXWikiContext();
        XWiki xwiki = deprecatedContext.getWiki();

        Workspace result = null;
        try {
            Wiki wikiDocument = wikiManagerInternal.getWikiFromName(workspaceId, deprecatedContext);
            if (wikiDocument == null || wikiDocument.isNew()) {
                return null;
            }

            XWikiServer wikiDescriptor = wikiDocument.getFirstWikiAlias();
            if (wikiDescriptor == null || wikiDescriptor.isNew()) {
                throw new WorkspaceManagerException(messageTool.get(WorkspaceManagerMessageTool.ERROR_WORKSPACEINVALID,
                    Arrays.asList(workspaceId)));
            }

            BaseObject workspaceObject = getWorkspaceObject(wikiDocument);
            if (workspaceObject == null) {
                return null;
            }

            DocumentReference groupReference =
                new DocumentReference(workspaceId, Workspace.WORKSPACE_GROUP_SPACE, Workspace.WORKSPACE_GROUP_PAGE);
            XWikiDocument groupDocument = xwiki.getDocument(groupReference, deprecatedContext);
            if (groupDocument == null || groupDocument.isNew()) {
                throw new WorkspaceManagerException(messageTool.get(WorkspaceManagerMessageTool.ERROR_WORKSPACEINVALID,
                    Arrays.asList(workspaceId)));
            }

            result = new DefaultWorkspace(wikiDocument, wikiDescriptor, new Document(groupDocument, deprecatedContext));
        } catch (Exception e) {
            logAndThrowException(
                messageTool.get(WorkspaceManagerMessageTool.ERROR_WORKSPACEGET,
                    Arrays.asList(workspaceId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())),
                e);
        }

        return result;
    }

    public List<Workspace> getWorkspaces() throws WorkspaceManagerException
    {
        List<Workspace> result = new ArrayList<Workspace>();

        try {
            List<Wiki> wikis = getWikiManager().getAllWikis();
            for (Wiki wiki : wikis) {
                try {
                    BaseObject workspaceObject = getWorkspaceObject(wiki);
                    if (workspaceObject != null) {
                        Workspace workspace = getWorkspace(wiki.getWikiName());
                        result.add(workspace);
                    }
                } catch (Exception e) {
                    /* Log and skip. */
                    getLogger().warn(
                        messageTool.get(WorkspaceManagerMessageTool.LOG_WORKSPACEINVALID,
                            Arrays.asList(wiki.getWikiName())), e);
                    continue;
                }
            }
        } catch (Exception e) {
            logAndThrowException(messageTool.get(WorkspaceManagerMessageTool.ERROR_WORKSPACEGETALL), e);
        }

        return result;
    }

    private BaseObject getWorkspaceObject(Wiki wikiDocument) throws XWikiException
    {
        XWikiContext deprecatedContext = getXWikiContext();

        // DocumentReference workspaceClassReference =
        // new DocumentReference(deprecatedContext.getMainXWiki(), "WorkspaceManager", "WorkspaceClass");
        // BaseObject workspaceObject = wikiDocument.getDocument().getXObject(workspaceClassReference);

        XWiki xwiki = deprecatedContext.getWiki();

        DocumentReference workspaceClassReference =
            new DocumentReference(deprecatedContext.getMainXWiki(), "WorkspaceManager", "WorkspaceClass");
        XWikiDocument xwikiCoreDocument = xwiki.getDocument(wikiDocument.getDocumentReference(), deprecatedContext);

        BaseObject workspaceObject = xwikiCoreDocument.getXObject(workspaceClassReference);

        return workspaceObject;
    }

    /**
     * Utility method to log and throw a {@link WorkspaceManagerException} wrapping a given exception.
     * 
     * @param message the error message to log.
     * @param e the exception to log and wrap in the thrown {@link WorkspaceManagerException}.
     * @throws WorkspaceManagerException when called.
     */
    private void logAndThrowException(String message, Exception e) throws WorkspaceManagerException
    {
        getLogger().error(message, e);
        throw new WorkspaceManagerException(message, e);
    }
}
