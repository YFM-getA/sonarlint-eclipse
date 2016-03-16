/*
 * SonarLint for Eclipse
 * Copyright (C) 2015-2016 SonarSource SA
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarlint.eclipse.ui.internal.bind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.core.databinding.beans.BeanProperties;
import org.eclipse.core.databinding.observable.list.WritableList;
import org.eclipse.core.databinding.property.value.IValueProperty;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.bindings.keys.IKeyLookup;
import org.eclipse.jface.bindings.keys.KeyLookupFactory;
import org.eclipse.jface.databinding.viewers.ViewerSupport;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnViewerEditor;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationStrategy;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.FocusCellHighlighter;
import org.eclipse.jface.viewers.FocusCellOwnerDrawHighlighter;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TableViewerEditor;
import org.eclipse.jface.viewers.TableViewerFocusCellManager;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.part.PageBook;
import org.sonarlint.eclipse.core.internal.resources.SonarLintProject;
import org.sonarlint.eclipse.core.internal.server.IServer;
import org.sonarlint.eclipse.core.internal.server.IServerLifecycleListener;
import org.sonarlint.eclipse.core.internal.server.ServersManager;
import org.sonarlint.eclipse.core.internal.utils.StringUtils;
import org.sonarlint.eclipse.ui.internal.Messages;
import org.sonarlint.eclipse.ui.internal.SonarLintImages;
import org.sonarlint.eclipse.ui.internal.server.wizard.NewServerLocationWizard;
import org.sonarsource.sonarlint.core.client.api.connected.RemoteModule;
import org.sonarsource.sonarlint.core.client.api.util.TextSearchIndex;

public class BindProjectsPage extends WizardPage {

  private final List<IProject> projects;
  private CheckboxTableViewer viewer;
  private Form noServersPage;
  private PageBook book;
  private IServerLifecycleListener serverListener;
  private IServer selectedServer;
  private Composite serverDropDownPage;
  private ComboViewer serverCombo;
  private Button autoBindBtn;

  public BindProjectsPage(List<IProject> projects) {
    super("bindProjects", "Bind with SonarQube", SonarLintImages.SONARWIZBAN_IMG);
    setDescription("Bind Eclipse project(s) with remote project/module on a SonarQube server");
    this.projects = projects;
  }

  @Override
  public void dispose() {
    if (serverListener != null) {
      ServersManager.getInstance().removeServerLifecycleListener(serverListener);
    }
  }

  @Override
  public void createControl(Composite parent) {
    Composite container = new Composite(parent, SWT.NONE);

    GridLayout layout = new GridLayout();
    layout.numColumns = 1;
    layout.marginHeight = 0;
    layout.marginWidth = 5;
    container.setLayout(layout);

    book = new PageBook(container, SWT.NONE);

    createNoServerForm(book);
    createServerDropDown(book);

    toggleServerPage();

    // List of projects
    viewer = CheckboxTableViewer.newCheckList(container, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL | SWT.VIRTUAL);
    viewer.getTable().setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true, 1, 3));

    viewer.getTable().setHeaderVisible(true);

    TableViewerColumn columnProject = new TableViewerColumn(viewer, SWT.LEFT);
    columnProject.getColumn().setText("Eclipse Project");
    columnProject.getColumn().setWidth(200);

    TableViewerColumn columnSonarProject = new TableViewerColumn(viewer, SWT.LEFT);
    columnSonarProject.getColumn().setText("SonarQube Project");
    columnSonarProject.getColumn().setWidth(600);

    columnSonarProject.setEditingSupport(new ProjectAssociationModelEditingSupport(viewer));

    List<ProjectBindModel> list = new ArrayList<>();
    for (IProject project : projects) {
      ProjectBindModel sonarProject = new ProjectBindModel(project);
      list.add(sonarProject);
    }

    ColumnViewerEditorActivationStrategy activationSupport = createActivationSupport();

    /*
     * Without focus highlighter, keyboard events will not be delivered to
     * ColumnViewerEditorActivationStragety#isEditorActivationEvent(...) (see above)
     */
    FocusCellHighlighter focusCellHighlighter = new FocusCellOwnerDrawHighlighter(viewer);
    TableViewerFocusCellManager focusCellManager = new TableViewerFocusCellManager(viewer, focusCellHighlighter);

    TableViewerEditor.create(viewer, focusCellManager, activationSupport, ColumnViewerEditor.TABBING_VERTICAL
      | ColumnViewerEditor.KEYBOARD_ACTIVATION);

    ViewerSupport.bind(
      viewer,
      new WritableList(list, ProjectBindModel.class),
      new IValueProperty[] {BeanProperties.value(ProjectBindModel.class, ProjectBindModel.PROPERTY_PROJECT_ECLIPSE_NAME),
        BeanProperties.value(ProjectBindModel.class, ProjectBindModel.PROPERTY_PROJECT_SONAR_FULLNAME)});

    Composite btnContainer = new Composite(container, SWT.NONE);

    FillLayout btnLayout = new FillLayout();
    btnContainer.setLayout(btnLayout);

    final Button unassociateBtn = new Button(btnContainer, SWT.PUSH);
    viewer.addSelectionChangedListener(new ISelectionChangedListener() {

      @Override
      public void selectionChanged(SelectionChangedEvent event) {
        unassociateBtn.setEnabled(viewer.getCheckedElements().length > 0);
        updateAutoBindState();
      }
    });

    unassociateBtn.setText("Unbind selected projects");
    unassociateBtn.setEnabled(!viewer.getStructuredSelection().isEmpty());
    unassociateBtn.addListener(SWT.Selection, new Listener() {

      @Override
      public void handleEvent(Event event) {
        for (Object object : viewer.getCheckedElements()) {
          ProjectBindModel bind = (ProjectBindModel) object;
          bind.unassociate();
        }
      }
    });

    autoBindBtn = new Button(btnContainer, SWT.PUSH);
    viewer.addSelectionChangedListener(new ISelectionChangedListener() {

      @Override
      public void selectionChanged(SelectionChangedEvent event) {
        updateAutoBindState();
      }
    });

    autoBindBtn.setText("Auto bind selected projects");
    updateAutoBindState();
    autoBindBtn.addListener(SWT.Selection, new Listener() {

      @Override
      public void handleEvent(Event event) {
        TextSearchIndex<RemoteModule> moduleIndex = selectedServer.getModuleIndex();
        for (Object object : viewer.getCheckedElements()) {
          ProjectBindModel bind = (ProjectBindModel) object;
          List<RemoteModule> results = moduleIndex.search(bind.getEclipseName());
          if (!results.isEmpty()) {
            bind.associate(selectedServer.getId(), results.get(0).getName(), results.get(0).getKey());
          }
        }
      }
    });

    setControl(btnContainer);
  }

  private void createServerDropDown(Composite parent) {
    serverDropDownPage = new Composite(parent, SWT.NONE);
    GridData layoutData = new GridData();
    layoutData.horizontalSpan = 2;
    serverDropDownPage.setLayoutData(layoutData);

    GridLayout layout = new GridLayout();
    layout.numColumns = 2;
    layout.marginHeight = 0;
    layout.marginWidth = 5;
    serverDropDownPage.setLayout(layout);

    Label labelField = new Label(serverDropDownPage, SWT.NONE);
    labelField.setText("Select server: ");
    serverCombo = new ComboViewer(serverDropDownPage, SWT.READ_ONLY);

    serverCombo.setContentProvider(ArrayContentProvider.getInstance());

    serverCombo.setLabelProvider(new LabelProvider() {
      @Override
      public String getText(Object element) {
        IServer current = (IServer) element;
        return current.getName();
      }
    });

    serverListener = new ServerChangeListener();

    ServersManager.getInstance().addServerLifecycleListener(serverListener);

    /* within the selection event, tell the object it was selected */
    serverCombo.addSelectionChangedListener(new ISelectionChangedListener() {
      @Override
      public void selectionChanged(SelectionChangedEvent event) {
        IStructuredSelection selection = (IStructuredSelection) event.getSelection();
        selectedServer = (IServer) selection.getFirstElement();
        serverCombo.refresh();
        updateAutoBindState();
      }

    });
  }

  private void updateAutoBindState() {
    if (selectedServer != null && !selectedServer.isSynced()) {
      setMessage("Server is not synced", IMessageProvider.WARNING);
    } else {
      setMessage("");
    }
    if (autoBindBtn != null) {
      autoBindBtn.setEnabled(viewer.getCheckedElements().length > 0 && selectedServer != null && selectedServer.isSynced());
    }
  }

  private void createNoServerForm(Composite parent) {
    FormToolkit toolkit = new FormToolkit(parent.getDisplay());
    noServersPage = toolkit.createForm(book);
    GridData layoutData = new GridData();
    layoutData.horizontalSpan = 2;
    noServersPage.setLayoutData(layoutData);

    Composite body = noServersPage.getBody();
    GridLayout layout = new GridLayout(2, false);
    body.setLayout(layout);

    Link hlink = new Link(body, SWT.NONE);
    hlink.setText(Messages.ServersView_noServers);
    hlink.setBackground(book.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
    GridData gd = new GridData(SWT.LEFT, SWT.FILL, true, false);
    hlink.setLayoutData(gd);
    hlink.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        NewServerLocationWizard wizard = new NewServerLocationWizard();
        WizardDialog wd = new WizardDialog(book.getShell(), wizard);
        if (wd.open() == Window.OK) {
          toggleServerPage();
        }
      }
    });
  }

  private void toggleServerPage() {
    List<IServer> servers = ServersManager.getInstance().getServers();
    if (servers.isEmpty()) {
      book.showPage(noServersPage);
      selectedServer = null;
    } else {
      book.showPage(serverDropDownPage);
      serverCombo.setInput(servers.toArray());
      serverCombo.setSelection(new StructuredSelection(servers.contains(selectedServer) ? selectedServer : servers.get(0)));
    }
  }

  private ColumnViewerEditorActivationStrategy createActivationSupport() {
    ColumnViewerEditorActivationStrategy activationSupport = new ColumnViewerEditorActivationStrategy(viewer) {
      @Override
      protected boolean isEditorActivationEvent(ColumnViewerEditorActivationEvent event) {
        return event.eventType == ColumnViewerEditorActivationEvent.TRAVERSAL
          || event.eventType == ColumnViewerEditorActivationEvent.MOUSE_CLICK_SELECTION
          || event.eventType == ColumnViewerEditorActivationEvent.PROGRAMMATIC
          || event.eventType == ColumnViewerEditorActivationEvent.KEY_PRESSED && event.keyCode == KeyLookupFactory
            .getDefault().formalKeyLookup(IKeyLookup.F2_NAME);
      }
    };
    activationSupport.setEnableEditorActivationWithKeyboard(true);
    return activationSupport;
  }

  private final class ServerChangeListener implements IServerLifecycleListener {

    @Override
    public void serverRemoved(IServer server) {
      updateServerPage();
    }

    @Override
    public void serverChanged(IServer server) {
      updateServerPage();
    }

    @Override
    public void serverAdded(IServer server) {
      updateServerPage();
    }

    private void updateServerPage() {
      getContainer().getShell().getDisplay().asyncExec(new Runnable() {
        @Override
        public void run() {
          toggleServerPage();
          getContainer().getShell().layout(true, true);
        }
      });
    }
  }

  private class ProjectAssociationModelEditingSupport extends EditingSupport {

    public ProjectAssociationModelEditingSupport(TableViewer viewer) {
      super(viewer);
    }

    @Override
    protected boolean canEdit(Object element) {
      return selectedServer != null && element instanceof ProjectBindModel;
    }

    @Override
    protected CellEditor getCellEditor(Object element) {
      return new TextCellEditorWithContentProposal(viewer.getTable(), new SearchEngineProvider(selectedServer, BindProjectsPage.this), (ProjectBindModel) element);
    }

    @Override
    protected Object getValue(Object element) {
      return StringUtils.trimToEmpty(((ProjectBindModel) element).getSonarFullName());
    }

    @Override
    protected void setValue(Object element, Object value) {
      // Don't set value as the model was already updated in the text adapter
    }

  }

  /**
   * Update all Eclipse projects when an association was provided:
   *   - enable Sonar nature
   *   - update sonar URL / key
   *   - refresh issues if necessary
   * @return
   */
  public boolean finish() {
    final ProjectBindModel[] projectAssociations = getProjects();
    for (ProjectBindModel projectAssociation : projectAssociations) {
      boolean changed = false;
      IProject project = projectAssociation.getProject();
      SonarLintProject sonarProject = SonarLintProject.getInstance(project);
      if (!Objects.equals(projectAssociation.getServerId(), sonarProject.getServerId())) {
        sonarProject.setServerId(projectAssociation.getServerId());
        changed = true;
      }
      if (!Objects.equals(projectAssociation.getModuleKey(), sonarProject.getModuleKey())) {
        sonarProject.setModuleKey(projectAssociation.getModuleKey());
        changed = true;
      }
      if (changed) {
        sonarProject.save();
      }
      if (changed && sonarProject.isBound()) {
        sonarProject.sync();
      }
    }
    return true;
  }

  private ProjectBindModel[] getProjects() {
    WritableList projectAssociations = (WritableList) viewer.getInput();
    return (ProjectBindModel[]) projectAssociations.toArray(new ProjectBindModel[projectAssociations.size()]);
  }

}