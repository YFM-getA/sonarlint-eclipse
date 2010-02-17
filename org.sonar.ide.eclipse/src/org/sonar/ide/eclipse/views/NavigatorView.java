package org.sonar.ide.eclipse.views;

import java.net.URL;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerSorter;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IStorageEditorInput;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.ui.part.DrillDownAdapter;
import org.eclipse.ui.part.ViewPart;
import org.sonar.ide.eclipse.SonarPlugin;
import org.sonar.ide.eclipse.Messages;
import org.sonar.ide.eclipse.SonarServerManager.IServerSetListener;
import org.sonar.ide.eclipse.views.model.TreeFile;
import org.sonar.ide.eclipse.views.model.TreeObject;
import org.sonar.ide.eclipse.views.model.TreeParent;
import org.sonar.ide.eclipse.views.model.TreeServer;
import org.sonar.ide.eclipse.wizards.NewServerLocationWizard;
import org.sonar.wsclient.Host;
import org.sonar.wsclient.Sonar;
import org.sonar.wsclient.services.Violation;
import org.sonar.wsclient.services.ViolationQuery;

/**
 * @author J�r�mie Lagarde
 * 
 */
public class NavigatorView extends ViewPart {

	public static final String ID = "org.sonar.ide.eclipse.views.NavigatorView";
	
	private TreeViewer viewer;
	private DrillDownAdapter drillDownAdapter;
	private Action deleteServerAction;
	private Action addServerAction;
	private Action doubleClickAction;
	private Action openSonarWebAction;
	private Action linkToEditorAction;
	private Action refreshAction;
	private boolean linking;
	

	private final IPartListener2 partListener2 = new IPartListener2() {
		public void partActivated(IWorkbenchPartReference ref) {
			if (ref.getPart(true) instanceof IEditorPart) {
				editorActivated(getViewSite().getPage().getActiveEditor());
			}
		}

		public void partBroughtToTop(IWorkbenchPartReference ref) {
			if (ref.getPart(true) == NavigatorView.this)
				editorActivated(getViewSite().getPage().getActiveEditor());
		}

		public void partClosed(IWorkbenchPartReference ref) {
		}

		public void partDeactivated(IWorkbenchPartReference ref) {
		}

		public void partOpened(IWorkbenchPartReference ref) {
			if (ref.getPart(true) == NavigatorView.this)
				editorActivated(getViewSite().getPage().getActiveEditor());
		}

		public void partHidden(IWorkbenchPartReference ref) {
		}

		public void partVisible(IWorkbenchPartReference ref) {
			if (ref.getPart(true) == NavigatorView.this)
				editorActivated(getViewSite().getPage().getActiveEditor());
		}

		public void partInputChanged(IWorkbenchPartReference ref) {
		}
	};

	class NameSorter extends ViewerSorter {
	}

	public NavigatorView() {
	}

	
	@Override
  public void createPartControl(Composite parent) {
		viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
		drillDownAdapter = new DrillDownAdapter(viewer);
		viewer.setContentProvider(new NavigatorContentProvider());
		viewer.setLabelProvider(new NavigatorLabelProvider());
		viewer.setSorter(new NameSorter());
		viewer.setInput(viewer);

		// Create the help context id for the viewer's control
		// PlatformUI.getWorkbench().getHelpSystem().setHelp(viewer.getControl(), "org.sonar.ide.eclipse.viewer");
		makeActions();
		hookContextMenu();
		hookDoubleClickAction();
		contributeToActionBars();

		SonarPlugin.getServerManager().addServerSetListener(new IServerSetListener() {

			public void serverSetChanged(int type, List<Host> serverList) {
				viewer.setContentProvider(new NavigatorContentProvider());		
			}
			
		});
		getSite().setSelectionProvider(viewer);		
		getSite().getPage().addPartListener(partListener2);
	}

	@Override
  public void dispose() {
		getSite().getPage().removePartListener(partListener2);
	}
	private void hookContextMenu() {
		MenuManager menuMgr = new MenuManager("#PopupMenu");
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager manager) {
				NavigatorView.this.fillContextMenu(manager);
			}
		});
		Menu menu = menuMgr.createContextMenu(viewer.getControl());
		viewer.getControl().setMenu(menu);
		getSite().registerContextMenu(menuMgr, viewer);
	}

	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalPullDown(bars.getMenuManager());
		fillLocalToolBar(bars.getToolBarManager());
	}

	private void fillLocalPullDown(IMenuManager manager) {
		manager.add(deleteServerAction);
		manager.add(new Separator());
		manager.add(addServerAction);
		manager.add(new Separator());
		manager.add(refreshAction);
		manager.add(new Separator());
		manager.add(linkToEditorAction);
	}

	private boolean shouldAddDeleteAction() {
		ISelection selection = viewer.getSelection();
		if (selection == null) {
			return false;
		}
		Object obj = ((IStructuredSelection)selection).getFirstElement();
		if (obj instanceof TreeServer) {
			return true;
		} else {
			return false;
		}
	}

	private boolean shouldAddOpenSonarWebAction() {
		ISelection selection = viewer.getSelection();
		if (selection == null) {
			return false;
		}
		Object obj = ((IStructuredSelection)selection).getFirstElement();
		if (obj instanceof TreeObject) {
			return true;
		} else {
			return false;
		}
	}
	private void fillContextMenu(IMenuManager manager) {
		if (shouldAddDeleteAction()) {
			manager.add(deleteServerAction);
		}
		manager.add(refreshAction);
		if (shouldAddOpenSonarWebAction()) {
			manager.add(openSonarWebAction);
		}
		drillDownAdapter.addNavigationActions(manager);
		manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
	}
	
	private void fillLocalToolBar(IToolBarManager manager) {
		manager.add(deleteServerAction);
		manager.add(addServerAction);
		manager.add(linkToEditorAction);
		manager.add(new Separator());
		drillDownAdapter.addNavigationActions(manager);
	}

	private void makeActions() {
		deleteServerAction = new Action() {
			@Override
      public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection)selection).getFirstElement();
				if (obj instanceof TreeServer) {
					String server = ((TreeServer)obj).getName();
					if (MessageDialog.openConfirm(NavigatorView.this.getSite().getShell(),
							                     Messages.getString("remove.server.dialog.caption"), //$NON-NLS-1$
							                     MessageFormat.format(Messages.getString("remove.server.dialog.msg"), //$NON-NLS-1$
							                                         new Object[] { server }))) {
					  SonarPlugin.getServerManager().removeServer(server);
					}
				}
			}
		};
		deleteServerAction.setText(Messages.getString("action.delete.server")); //$NON-NLS-1$
		deleteServerAction.setToolTipText(Messages.getString("action.delete.server.desc")); //$NON-NLS-1$
		deleteServerAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
			getImageDescriptor(ISharedImages.IMG_TOOL_DELETE));
		
		addServerAction = new Action() {
			@Override
      public void run() {
				NewServerLocationWizard wiz = new NewServerLocationWizard();
				wiz.init(SonarPlugin.getDefault().getWorkbench(), null);
				WizardDialog dialog = 
					new WizardDialog(NavigatorView.this.getSite().getShell(), wiz);
			    dialog.create();
			    dialog.open();
			}
		};
		addServerAction.setText(Messages.getString("action.add.server")); //$NON-NLS-1$
		addServerAction.setToolTipText(Messages.getString("action.add.server.desc")); //$NON-NLS-1$
		addServerAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
				getImageDescriptor(ISharedImages.IMG_TOOL_NEW_WIZARD));
		
		doubleClickAction = new Action() {
			@Override
      public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection)selection).getFirstElement();
				if (obj instanceof TreeObject) {
					doubleClick((TreeObject)obj);
				}
			}
		};
		
		refreshAction = new Action() {
			@Override
      public void run() {
				ISelection selection = viewer.getSelection();
				if (selection instanceof IStructuredSelection) {
					IStructuredSelection strucSel = (IStructuredSelection)selection;
					if (!strucSel.isEmpty()) {
						viewer.refresh(strucSel.getFirstElement());
					}
				}
			}
		};
		refreshAction.setText(Messages.getString("action.refresh.server")); //$NON-NLS-1$
		refreshAction.setToolTipText(Messages.getString("action.refresh.server.desc")); //$NON-NLS-1$
		refreshAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
				getImageDescriptor(ISharedImages.IMG_TOOL_REDO));
		
		openSonarWebAction = new Action() {
			@Override
      public void run() {
				ISelection selection = viewer.getSelection();
				if (selection instanceof IStructuredSelection) {
					IStructuredSelection strucSel = (IStructuredSelection)selection;
					if (!strucSel.isEmpty()) {
						openBrowser((TreeObject)strucSel.getFirstElement());
					}
				}
			}
		};
		openSonarWebAction.setText(Messages.getString("action.open")); //$NON-NLS-1$
		openSonarWebAction.setToolTipText(Messages.getString("action.open.desc")); //$NON-NLS-1$
		openSonarWebAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
				getImageDescriptor(ISharedImages.IMG_TOOL_REDO));
		

		linkToEditorAction = new Action(Messages.getString("action.link"), IAction.AS_CHECK_BOX) {
			@Override
      public void run() {
				toggleLinking(isChecked());
			}
		};
		linkToEditorAction.setToolTipText(Messages.getString("action.link.desc")); //$NON-NLS-1$
		linkToEditorAction.setImageDescriptor(SonarPlugin.getImageDescriptor(SonarPlugin.IMG_SONARSYNCHRO));
	}
	
	private void hookDoubleClickAction() {
		viewer.addDoubleClickListener(new IDoubleClickListener() {
			public void doubleClick(DoubleClickEvent event) {
				doubleClickAction.run();
			}
		});
	}

	private void doubleClick(TreeObject node) {
		if (node instanceof TreeParent) {
			if (viewer.getExpandedState(node)) {
				viewer.collapseToLevel(node, 1);
			} else {
				viewer.expandToLevel(node, viewer.ALL_LEVELS);
			}
		}
		if (node instanceof TreeFile) {
			openBrowser(node);
		}
	}
	
	private void openBrowser(TreeObject selectedNode) {
		if (selectedNode == null) {
			return;
		} 
		IWorkbenchBrowserSupport browserSupport = 
		  SonarPlugin.getDefault().getWorkbench().getBrowserSupport();
		try {
			URL consoleURL = new URL(
					extractSonarUrl(selectedNode));
			if (browserSupport.isInternalWebBrowserAvailable()) {
				browserSupport.createBrowser("id"+selectedNode.getServer().hashCode()).openURL(consoleURL);
			} else {
				browserSupport.getExternalBrowser().openURL(consoleURL);
			}
		} catch (Exception e) {
		  SonarPlugin.getDefault().displayError(IStatus.ERROR, e.getMessage(), e, true);
		}
	}

	protected void toggleLinking(boolean checked) {
		this.linking = checked;

		if (this.linking)
			editorActivated(getSite().getPage().getActiveEditor());

	}


	void editorActivated(IEditorPart editor) {
		if(editor==null)
			return;
		IEditorInput editorInput= editor.getEditorInput();
		if (editorInput == null)
			return;
		Object input= getInputFromEditor(editorInput);
		if (input == null)
			return;
		if (!inputIsSelected(editorInput))
			showInput(input);
		else
			viewer.getTree().showSelection();
	}
	

	private Object getInputFromEditor(IEditorInput editorInput) {
		Object input= JavaUI.getEditorInputJavaElement(editorInput);
		if (input instanceof ICompilationUnit) {
			ICompilationUnit cu= (ICompilationUnit) input;
			if (!cu.getJavaProject().isOnClasspath(cu)) { // test needed for Java files in non-source folders (bug 207839)
				input= cu.getResource();
			}
		}
		if (input == null) {
			input= editorInput.getAdapter(IFile.class);
		}
		if (input == null && editorInput instanceof IStorageEditorInput) {
			try {
				input= ((IStorageEditorInput) editorInput).getStorage();
			} catch (CoreException e) {
				// ignore
			}
		}
		return input;
	}

	private boolean inputIsSelected(IEditorInput input) {
		IStructuredSelection selection= (IStructuredSelection)viewer.getSelection();
		if (selection.size() != 1)
			return false;

		Object element = selection.getFirstElement();
		// IEditorInput selectionAsInput= EditorUtility.getEditorInput(selection.getFirstElement());
		// return input.equals(selectionAsInput);
		return false;
	}

	boolean showInput(Object input) {
		Object element= null;

		if (input instanceof IFile) {
			element= JavaCore.create((IFile)input);
		}

		if (element == null) // try a non Java resource
			element= input;

		TreeObject treeObject = null;
		if(input instanceof IJavaElement) {
			String name   = ((IJavaElement) input).getElementName();
			String parent = ((IJavaElement) input).getParent().getElementName();
			treeObject = ((NavigatorContentProvider)viewer.getContentProvider()).find(name);
		}
			
		if (treeObject != null) {
			ISelection newSelection= new StructuredSelection(treeObject);
			if (viewer.getSelection().equals(newSelection)) {
				viewer.reveal(element);
			} else {
				viewer.setSelection(newSelection, true);
//				while (element != null && viewer.getSelection().isEmpty()) {
//					// Try to select parent in case element is filtered
//					element= getParent(element);
//					if (element != null) {
//						newSelection= new StructuredSelection(element);
//						viewer.setSelection(newSelection, true);
//					}
//				}
			}
			IResource file = null;
			if (input instanceof IFile) {
				file = (IFile) input;
			}else if (element instanceof ICompilationUnit) {
				file = ((ICompilationUnit)element).getResource();
			}
			if (file != null) {
				Sonar sonar = treeObject.getServer();
			    ViolationQuery query = ViolationQuery.createForResource(treeObject.getResource());
			    Collection<Violation> violations = sonar.findAll(query);
				try {
					for (Violation violation : violations) {
						IMarker marker = ((IFile)file).createMarker(IMarker.TASK);
						if (marker.exists()) {
							marker.setAttribute(IMarker.MESSAGE, "sonar : " + violation.getMessage());
							marker.setAttribute(IMarker.LINE_NUMBER, violation.getLine());
							marker.setAttribute(IMarker.PRIORITY, IMarker.PRIORITY_HIGH);
							marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
						}
					}
				} catch (CoreException e) {
					// You need to handle the case where the marker no longer exists      }
			    }
			}
			return true;
		}
		return false;
	}

	
	private String extractSonarUrl(TreeObject treeObject) {
		return treeObject.getRemoteURL();
	}
	
	@Override
  public void setFocus() {
		viewer.getControl().setFocus();
	}
}