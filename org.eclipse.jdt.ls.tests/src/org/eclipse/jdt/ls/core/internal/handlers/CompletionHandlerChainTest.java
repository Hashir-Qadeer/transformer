package org.eclipse.jdt.ls.core.internal.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.manipulation.CoreASTProvider;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JsonMessageHelper;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CompletionHandlerChainTest extends AbstractCompilationUnitBasedTest {
	private DocumentLifeCycleHandler lifeCycleHandler;
	private JavaClientConnection javaClient;

	private static String COMPLETION_TEMPLATE = """
			{
			    "id": "1",
			    "method": "textDocument/completion",
			    "params": {
			        "textDocument": {
			            "uri": "${file}"
			        },
			        "position": {
			            "line": ${line},
			            "character": ${char}
			        },
					"context": {
						"triggerKind": 1
					}
			    },
			    "jsonrpc": "2.0"
			}""";

	@Before
	public void setUp() {
		mockLSP3Client();
		CoreASTProvider sharedASTProvider = CoreASTProvider.getInstance();
		sharedASTProvider.disposeAST();
		javaClient = new JavaClientConnection(client);
		lifeCycleHandler = new DocumentLifeCycleHandler(javaClient, preferenceManager, projectsManager, true);
		preferences.setPostfixCompletionEnabled(false);
		preferences.setChainCompletionEnabled(true);
	}

	@After
	public void tearDown() throws Exception {
		javaClient.disconnect();
	}

	private CompletionList requestCompletions(ICompilationUnit unit, String completeBehind) throws JavaModelException {
		return requestCompletions(unit, completeBehind, 0);
	}

	private CompletionList requestCompletions(ICompilationUnit unit, String completeBehind, int fromIndex) throws JavaModelException {
		int[] loc = findCompletionLocation(unit, completeBehind, fromIndex);
		return server.completion(JsonMessageHelper.getParams(createCompletionRequest(unit, loc[0], loc[1]))).join().getRight();
	}

	private String createCompletionRequest(ICompilationUnit unit, int line, int kar) {
		return COMPLETION_TEMPLATE.replace("${file}", JDTUtils.toURI(unit)).replace("${line}", String.valueOf(line)).replace("${char}", String.valueOf(kar));
	}

	private void mockLSP3Client() {
		mockLSPClient(true, true);
	}

	private void mockLSP2Client() {
		mockLSPClient(false, false);
	}

	private void mockLSPClient(boolean isSnippetSupported, boolean isSignatureHelpSuported) {
		// Mock the preference manager to use LSP v3 support.
		when(preferenceManager.getClientPreferences().isCompletionSnippetsSupported()).thenReturn(isSnippetSupported);
	}

	@Test
	public void testChainCompletionsOnParameter() throws Exception {
		//@formatter:off
			ICompilationUnit unit = getWorkingCopy(
					"src/java/Foo.java",
					"""
						import java.util.stream.Stream;
						public class Foo {
						    public static void main(String[] args) {
								Stream.of("1").collect()
						    }
						}
						""");
		//@formatter:on
		CompletionList list = requestCompletions(unit, "collect(");
		List<CompletionItem> completionItems = list.getItems().stream().filter(i -> i.getLabel().contains("toList")).collect(Collectors.toList());
		assertEquals("toList completion count", 1, completionItems.size());

		CompletionItem completionItem = completionItems.get(0);
		assertNotNull(completionItem);
		assertEquals("Completion getTextEditText", "Collectors.toList()", completionItem.getTextEditText());
		assertNotNull(completionItem.getLabelDetails());
		assertEquals("Completion detail.description", "java.util.stream.Collector<T,?,java.util.List<T>>", completionItem.getLabelDetails().getDescription());
		assertEquals("Completion detail.detail", " - Collectors.toList()", completionItem.getLabelDetails().getDetail());
		assertNotNull(completionItem.getAdditionalTextEdits());
		assertEquals("Additional edits count", 1, completionItem.getAdditionalTextEdits().size());
		assertNotNull(completionItem.getAdditionalTextEdits().get(0));
		assertEquals("Import", "import java.util.stream.Collectors;\n", completionItem.getAdditionalTextEdits().get(0).getNewText());
		assertEquals("Completion Label", "toList()", completionItem.getLabel());
	}

	@Test
	public void testChainCompletionsOnVariable() throws Exception {
		//@formatter:off
			ICompilationUnit unit = getWorkingCopy(
					"src/java/Foo.java",
					"""
						import java.util.List;
						public class Foo {
						    public static void main(String[] args) {
								List<String> names =
						    }
						}
						""");
		//@formatter:on
		CompletionList list = requestCompletions(unit, "names =");
		List<CompletionItem> completionItems = list.getItems().stream().filter(i -> i.getLabel().contains("emptyList")).collect(Collectors.toList());
		assertEquals("emptyList completion count", 1, completionItems.size());

		CompletionItem completionItem = completionItems.get(0);
		assertNotNull(completionItem);
		assertEquals("Completion getTextEditText", "Collections.emptyList()", completionItem.getTextEditText());

		assertNotNull(completionItem.getAdditionalTextEdits());
		assertEquals("Additional edits count", 1, completionItem.getAdditionalTextEdits().size());
		assertNotNull(completionItem.getAdditionalTextEdits().get(0));
		assertEquals("Import", "import java.util.Collections;\n", completionItem.getAdditionalTextEdits().get(0).getNewText());
	}

	@Test
	public void testChainCompletionsOnVariableWithNewKeywordExpectNoChains() throws Exception {
		//@formatter:off
			ICompilationUnit unit = getWorkingCopy(
					"src/java/Foo.java",
					"""
						import java.util.List;
						public class Foo {
						    public static void main(String[] args) {
								List<String> names = new
						    }
						}
						""");
		//@formatter:on
		CompletionList list = requestCompletions(unit, "names = new");
		List<CompletionItem> completionItems = list.getItems().stream().filter(i -> i.getLabel().endsWith("emptyList() <T>")).collect(Collectors.toList());
		assertEquals("emptyList completion count", 0, completionItems.size());
	}

	@Test
	public void testChainCompletionsOnVariableCompletingConstructorExpectNoChains() throws Exception {
		//@formatter:off
			ICompilationUnit unit = getWorkingCopy(
					"src/java/Foo.java",
					"""
						import java.util.List;
						public class Foo {
						    public static void main(String[] args) {
								List<String> names = new Arr
						    }
						}
						""");
		//@formatter:on
		CompletionList list = requestCompletions(unit, "names = new Arr");
		List<CompletionItem> completionItems = list.getItems().stream().filter(i -> i.getLabel().endsWith("emptyList() <T>")).collect(Collectors.toList());
		assertEquals("emptyList completion count", 0, completionItems.size());
	}

	@Test
	public void testChainCompletionsOnPrimitiveVariableExpectNoCompletions() throws Exception {
		//@formatter:off
			ICompilationUnit unit = getWorkingCopy(
					"src/java/Foo.java",
					"""
						public class Foo {
						    public static boo(IntChain chain) {
								Integer variable = ;
						    }

							static class IntChain {
								public Integer newInt() {
									return 1;
								}
							}
						}
						""");
		//@formatter:on
		CompletionList list = requestCompletions(unit, "variable = ");
		List<CompletionItem> completionItems = list.getItems().stream().filter(i -> i.getLabel().contains("newInt")).collect(Collectors.toList());
		assertEquals("emptyList completion count", 0, completionItems.size());
	}

	@Test
	public void testChainCompletionsOnStringVariableExpectNoCompletions() throws Exception {
		//@formatter:off
			ICompilationUnit unit = getWorkingCopy(
					"src/java/Foo.java",
					"""
						public class Foo {
						    public static boo(StringChain chain) {
								String variable = //
								"variable".concat("");
						    }

							static class StringChain {
								public String newString() {
									return "";
								}
							}
						}
						""");
		//@formatter:on
		CompletionList list = requestCompletions(unit, "variable = ");
		List<CompletionItem> completionItems = list.getItems().stream().filter(i -> i.getLabel().contains("newString")).collect(Collectors.toList());
		assertEquals("emptyList completion count [binding]", 0, completionItems.size());

		list = requestCompletions(unit, "\"variable\".concat(");
		completionItems = list.getItems().stream().filter(i -> i.getLabel().contains("newString")).collect(Collectors.toList());
		assertEquals("emptyList completion count [type]", 0, completionItems.size());
	}

	@Test
	public void testChainCompletionsOnObjectVariableExpectNoCompletions() throws Exception {
		//@formatter:off
			ICompilationUnit unit = getWorkingCopy(
					"src/java/Foo.java",
					"""
						public class Foo {
						    public static boo(ObjectChain chain) {
								Object variable = //
								chain.equals(variable);
						    }

							static class ObjectChain {
								public Object newObject() {
									return new Object();
								}
							}
						}
						""");
		//@formatter:on
		CompletionList list = requestCompletions(unit, "variable = ");
		List<CompletionItem> completionItems = list.getItems().stream().filter(i -> i.getLabel().contains("newObject")).collect(Collectors.toList());
		assertEquals("emptyList completion count", 0, completionItems.size());

		list = requestCompletions(unit, "chain.equals(");
		completionItems = list.getItems().stream().filter(i -> i.getLabel().contains("newObject")).collect(Collectors.toList());
		assertEquals("emptyList completion count [type]", 0, completionItems.size());
	}
}
