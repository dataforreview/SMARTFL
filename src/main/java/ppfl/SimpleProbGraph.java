package ppfl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Stack;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;

public class SimpleProbGraph {

	private List<FactorNode> factornodes;
	private List<Node> nodes;
	private List<StmtNode> stmts;
	private Map<String, Node> nodemap;
	private Map<String, Node> stmtmap;
	private Map<String, Integer> varcountmap;
	private Map<String, Integer> stmtcountmap;
	// if max_loop is set to negative, then no limit is set(unlimited loop
	// unfolding)
	int max_loop;

	private StmtNode callernode;
	private Line caller;
	private List<Set<String>> passedargs;
	private Set<Integer> passedpreds;
	private List<List<String>> passedops;
	private Stack<String> returnDef;

	private String testname;

	private int nsamples = 1 << 20;
	private int bp_times = 100;
	Random random;

	LineInfo lineinfo;
	Map<String, LineInfo> lineinfomap;
	// when set to true, lineinfomap will be enabled.
	boolean parseproject;

	// auto-oracle: when set to TRUE, parsetrace() will auto-assign prob for:
	// input of test function as 1.0(always true)
	// output (return value) of the function being tested as 0.0/1.0 depends on
	// parameter testpass(true = 1.0,false = 0.0)
	private boolean auto_oracle;

	// if this is set to TRUE, a factor of the following will be added for statement
	// "a = foo (b)":
	// def : a
	// use : b
	// this could avoid call stack breakdown when some functions are not traced(e.g.
	// libraries)
	private boolean add_return_arg_factor;

	public SimpleProbGraph() {
		factornodes = new ArrayList<FactorNode>();
		nodes = new ArrayList<Node>();
		stmts = new ArrayList<StmtNode>();
		nodemap = new HashMap<String, Node>();
		stmtmap = new HashMap<String, Node>();
		varcountmap = new HashMap<String, Integer>();
		stmtcountmap = new HashMap<String, Integer>();
		max_loop = -1;
		random = new Random();
		auto_oracle = true;
		returnDef = new Stack<String>();
		add_return_arg_factor = true;
		this.lineinfomap = new HashMap<String, LineInfo>();
	}

	// public Graph(String tracefilename, String testname, boolean testpass, boolean
	// _auto_oracle) {
	// this.testname = testname;
	// factornodes = new ArrayList<FactorNode>();
	// nodes = new ArrayList<Node>();
	// stmts = new ArrayList<StmtNode>();
	// nodemap = new HashMap<String, Node>();
	// stmtmap = new HashMap<String, Node>();
	// varcountmap = new HashMap<String, Integer>();
	// stmtcountmap = new HashMap<String, Integer>();
	// max_loop = -1;
	// random = new Random();
	// auto_oracle = _auto_oracle;
	// parsetrace(tracefilename, testname, testpass);
	// }

	public void setMaxLoop(int i) {
		this.max_loop = i;
	}

	public void setAutoOracle(boolean b) {
		this.auto_oracle = b;
	}

	public void setParseProject(boolean b) {
		this.parseproject = b;
	}

	public void setAddReturnArgFactor(boolean b) {
		this.add_return_arg_factor = b;
	}

	public void parsesource(String sourcefilename) {
		final String FilePath = sourcefilename;
		ASTParser parser = ASTParser.newParser(AST.JLS3);
		String source = readFileToString(FilePath);
		parser.setSource(source.toCharArray());
		parser.setKind(ASTParser.K_COMPILATION_UNIT);

		final CompilationUnit cu = (CompilationUnit) parser.createAST(null);
		this.lineinfo = new LineInfo(cu);
		ASTVisitor visitor = new LineMappingVisitor(this.lineinfo);
		cu.accept(visitor);
		lineinfo.print();
	}

	public void parsesourcefile(String sourcefilename, String classname, boolean verbose) {
		final String FilePath = sourcefilename;
		ASTParser parser = ASTParser.newParser(AST.JLS3);
		String source = readFileToString(FilePath);
		parser.setSource(source.toCharArray());
		parser.setKind(ASTParser.K_COMPILATION_UNIT);

		final CompilationUnit cu = (CompilationUnit) parser.createAST(null);
		LineInfo lf = new LineInfo(cu);
		ASTVisitor visitor = new LineMappingVisitor(lf);
		cu.accept(visitor);
		if (verbose) {
			lf.print();
		}
		this.lineinfomap.put(classname, lf);
	}

	public void parsesourcefolder(File folder, String prefix, boolean verbose) {
		if (folder.exists() && folder.isDirectory()) {
			File[] files = folder.listFiles();
			for (File file : files) {
				if (file.isDirectory()) {
					parsesourcefolder(file, prefix + "." + folder.getName(), verbose);
				}
				if (file.isFile() && file.getName().endsWith(".")) {
					parsesourcefile(file.getAbsolutePath(), prefix + "." + folder.getName(), verbose);
				}
			}
		} else {
			System.out.println("Not a folder");
		}
	}

	public void parseproject(String sourcefolder, String prefix, boolean verbose) {
		File folder = new File(sourcefolder);
		parsesourcefolder(folder, prefix, verbose);
	}

	public void parsetrace(String tracefilename, String testname, boolean testpass) {
		this.testname = testname;
		varcountmap = new HashMap<String, Integer>();
		try {
			BufferedReader reader = new BufferedReader(new FileReader(tracefilename));
			String t;
			String last_defined_var = null;
			StmtNode last_defined_stmt = null;
			while ((t = reader.readLine()) != null) {
				if (t.isEmpty() || t.startsWith("###"))
					continue;
				String[] split = t.split(":");
				assert (split.length >= 2);
				String domain = split[0];// TODO utilize this to search lineinfo in different files.
				int splitp = domain.lastIndexOf('.');
				String traceclass = domain.substring(0, splitp);
				String trace_method = domain.substring(splitp + 1);

				LineInfo lineinfo;
				if (parseproject) {
					lineinfo = this.lineinfomap.get(traceclass);
				} else {
					lineinfo = this.lineinfo;
				}
				int line = Integer.parseInt(split[1]);
				Line curline = lineinfo.getLine(line);
				StmtNode stmt = null;
				String stmtname = domain + "#" + String.valueOf(line);
				// System.out.println("At line " + stmtname);
				if (!hasNode(stmtname)) {
					stmt = new StmtNode(stmtname, 0);
					addNode(stmtname, stmt);

				} else {
					stmt = (StmtNode) getNode(stmtname);
					assert (stmt.isStmt());
				}

				// count how many times this statment has been executed
				if (stmtcountmap.containsKey(stmtname)) {
					stmtcountmap.put(stmtname, stmtcountmap.get(stmtname) + 1);
				} else {
					stmtcountmap.put(stmtname, 1);
				}
				if (max_loop > 0 && stmtcountmap.get(stmtname) > max_loop) {
					continue;
				}

				// auto-assigned observation: test function always true
				if (auto_oracle) {
					if (trace_method.contentEquals(testname)) {
						stmt.observe(true);
						System.out.println("Observe " + stmt.getName() + " as true");
					}
				}

				// deal with inter-procedure call
				if (curline.ismethod) {
					// this is the very first statement inside a method.
					if (passedargs != null && !passedargs.isEmpty()) {
						// if caller exist. This should apply in most cases(except program main entry,
						// parameter passed from command line)
						assert (curline.argdefs.size() == passedargs.size());
						for (int i = 0; i < passedargs.size(); i++) {
							String d = curline.argdefs.get(i);
							Set<String> arguses = passedargs.get(i);
							List<String> argops = passedops.get(i);
							FactorNode factor = buildFactor(d, passedpreds, arguses, argops, callernode);
							last_defined_var = d;
							last_defined_stmt = callernode;
						}
					}
				}
				if (curline.isret) {
					// if caller exist.
					// System.out.println("parsing ret stmt:");
					// curline.print();
					if (!returnDef.isEmpty()) {
						String retdef = returnDef.pop();
						FactorNode factor = buildFactor(retdef, curline.preds, curline.retuses, curline.retops, callernode);
						// record last defined value(used in auto-oracle)
						last_defined_var = retdef;
						last_defined_stmt = callernode;
					}
				}

				if (curline.ismethodinvocation) {
					callernode = stmt;
					caller = curline;
					if (curline.def != null) {
						returnDef.push(curline.def);
					} else
						returnDef.push(curline.retdef);
					passedargs = curline.arguses;
					passedpreds = curline.preds;
					passedops = curline.argops;
				}

				if (curline.def != null) {
					if (curline.ismethodinvocation && curline.retdef != null) {
						// add this factor to deal with libraries.
						// when no library method is used, this factor could be ignored.
						if (add_return_arg_factor) {
							FactorNode factor = buildFactorWithArgUses(curline.def, curline.preds, curline.arguses, curline.ops,
									stmt);
						}
					} else {
						FactorNode factor = buildFactor(curline.def, curline.preds, curline.uses, curline.ops, stmt);
					}
					// System.out.println("printing curline.def");
					// curline.print();

					// record last defined value(used in auto-oracle)
					last_defined_var = curline.def;
					last_defined_stmt = stmt;
				}

				if (curline.preddef != null) {
					// System.out.println("printing curline.preddef");
					// curline.print();
					FactorNode factor = buildFactor(curline.preddef, curline.preds, curline.preduses, curline.predops, stmt);
					// record last defined value(used in auto-oracle)
					last_defined_var = curline.preddef;
					last_defined_stmt = stmt;
				}

			}
			// after all lines are parsed, auto-assign oracle for the last defined var
			// with test state(pass = true,fail = false)
			if (auto_oracle) {
				String observename = getVarName(last_defined_var, varcountmap);
				this.observe(observename, testpass);
				System.out.println("Observe " + observename + " as " + testpass);
			}

			reader.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private FactorNode buildFactorWithArgUses(String def, Set<Integer> preds, List<Set<String>> uses, List<String> ops,
			StmtNode stmt) {
		HashSet<String> t = new HashSet<String>();
		for (Set<String> use : uses) {
			t.addAll(use);
		}
		return buildFactor(def, preds, t, ops, stmt);
	}

	private FactorNode buildFactor(String def, Set<Integer> preds, Set<String> uses, List<String> ops, StmtNode stmt) {

		// deal with Declaration and use/pred in the same line
		// (e.g. for(int i = 0;i < n;i++))
		// In this case, some use/pred are not in varcountmap.
		Node defnode = null;
		boolean initDef = false;
		if (preds != null)
			for (Integer i : preds) {
				String s = LineMappingVisitor.getPredName(i);
				if (!varcountmap.containsKey(s))
					initDef = true;
			}
		if (uses != null)
			for (String s : uses) {
				if (!s.equals(LineMappingVisitor.getConstName())) {// TODO deal with constants.
					if (!varcountmap.containsKey(s))
						initDef = true;
				}
			}
		// Add def earlier. for(int i = 1;i < n;i++) def:i#1 use:i#1
		if (initDef) {
			System.out.println("initdef " + def);
			if (!varcountmap.containsKey(def)) {
				varcountmap.put(def, 1);
			} else {
				varcountmap.put(def, varcountmap.get(def) + 1);
			}
			String defname = getVarName(def, varcountmap);
			defnode = new Node(defname, testname, stmt);
			// System.out.println("Adding def: " + defname);
			addNode(defname, defnode);
		}

		List<Node> prednodes = new ArrayList<Node>();
		if (preds != null)
			for (Integer i : preds) {
				String s = LineMappingVisitor.getPredName(i);
				if (!varcountmap.containsKey(s)) {
					// assert(i == )
					continue;
				}
				String predname = getVarName(s, varcountmap);
				prednodes.add(getNode(predname));
			}
		List<Node> usenodes = new ArrayList<Node>();
		if (uses != null)
			for (String s : uses) {
				if (!s.equals(LineMappingVisitor.getConstName())) {// TODO deal with constants.
					// System.out.print(s + " ");
					if (!varcountmap.containsKey(s)) {
						System.out.println("Undefined use:" + s);
						// assert(false);
						continue;
					}
					assert (varcountmap.containsKey(s));
					String usename = getVarName(s, varcountmap);
					// System.out.println("Setting uses: " + usename);
					usenodes.add(getNode(usename));
				}
			}

		// deal with def here.
		// when a = a + 1; occurs, use should be a#1, def should be a#2
		if (!initDef) {
			if (!varcountmap.containsKey(def)) {
				varcountmap.put(def, 1);
			} else {
				varcountmap.put(def, varcountmap.get(def) + 1);
			}
			String defname = getVarName(def, varcountmap);
			defnode = new Node(defname, testname, stmt);
			// System.out.println("Adding def: " + defname);
			addNode(defname, defnode);
		}

		Edge dedge = new Edge();
		dedge.setnode(defnode);
		defnode.add_edge(dedge);
		defnode.setdedge(dedge);
		Edge sedge = new Edge();
		sedge.setnode(stmt);
		stmt.add_edge(sedge);

		List<Edge> pedges = new ArrayList<Edge>();
		for (Node n : prednodes) {
			Edge nedge = new Edge();
			nedge.setnode(n);
			pedges.add(nedge);
			n.add_edge(nedge);
		}
		List<Edge> uedges = new ArrayList<Edge>();
		for (Node n : usenodes) {
			Edge nedge = new Edge();
			nedge.setnode(n);
			uedges.add(nedge);
			n.add_edge(nedge);
		}
		FactorNode ret = new FactorNode(defnode, stmt, prednodes, usenodes, ops, dedge, sedge, pedges, uedges);
		factornodes.add(ret);

		dedge.setfactor(ret);
		sedge.setfactor(ret);
		for (Edge n : pedges)
			n.setfactor(ret);
		for (Edge n : uedges)
			n.setfactor(ret);
		return ret;
	}

	private String getVarName(String name, Map<String, Integer> map) {
		int count = map.get(name);
		return name + "#" + String.valueOf(count);
	}

	private String getNodeName(String name) {
		return this.testname + "#" + name;
	}

	private boolean hasNode(String name) {
		return nodemap.containsKey(getNodeName(name)) || stmtmap.containsKey(name);
	}

	private void addNode(String name, Node node) {
		if (node instanceof StmtNode) {
			stmtmap.put(name, node);
			stmts.add((StmtNode) node);
		} else {
			nodemap.put(getNodeName(name), node);
			nodes.add(node);
		}
	}

	private Node getNode(String name) {
		if (nodemap.containsKey(getNodeName(name)))
			return nodemap.get(getNodeName(name));
		else if (stmtmap.containsKey(name))
			return stmtmap.get(name);
		else
			return null;
	}

	private void solve(List<Node> allnodes, int cur, int tot) {
		if (cur == tot) {
			double product = 1.0;
			for (FactorNode n : factornodes) {
				product = product * n.getProb();// TODO consider log-add
			}
			for (Node n : nodes) {
				n.addimp(product);
			}
			for (Node n : stmts) {
				n.addimp(product);
			}
			return;
		}
		allnodes.get(cur).setTemp(true);
		solve(allnodes, cur + 1, tot);
		allnodes.get(cur).setTemp(false);
		solve(allnodes, cur + 1, tot);
	}

	public long bf_inference() {
		long startTime = System.currentTimeMillis();
		for (Node n : nodes) {
			n.init();
		}
		for (Node n : stmts) {
			n.init();
		}

		List<Node> allnodes = new ArrayList<Node>();
		allnodes.addAll(nodes);
		allnodes.addAll(stmts);
		int nnodes = allnodes.size();
		solve(allnodes, 0, nnodes);

		nodes.sort(new Comparator<Node>() {
			@Override
			public int compare(Node arg0, Node arg1) {
				return Double.compare(arg0.getprob(), arg1.getprob());
			}
		});
		stmts.sort(new Comparator<Node>() {
			@Override
			public int compare(Node arg0, Node arg1) {
				return Double.compare(arg0.getprob(), arg1.getprob());
			}
		});
		long endTime = System.currentTimeMillis();
		return (endTime - startTime);
	}

	public void inference() {
		for (Node n : nodes) {
			n.init();
		}
		for (Node n : stmts) {
			n.init();
		}
		for (int i = 0; i < nsamples; i++) {
			for (Node n : nodes) {
				n.setTemp(random.nextBoolean());
			}
			for (Node n : stmts) {
				n.setTemp(random.nextBoolean());
			}
			double product = 1.0;
			for (FactorNode n : factornodes) {
				product = product * n.getProb();// TODO consider log-add
			}
			for (Node n : nodes) {
				n.addimp(product);
			}
			for (Node n : stmts) {
				n.addimp(product);
			}
		}

		nodes.sort(new Comparator<Node>() {
			@Override
			public int compare(Node arg0, Node arg1) {
				return Double.compare(arg0.getprob(), arg1.getprob());
			}
		});
		stmts.sort(new Comparator<Node>() {
			@Override
			public int compare(Node arg0, Node arg1) {
				return Double.compare(arg0.getprob(), arg1.getprob());
			}
		});
		return;
	}

	private void mark_reduce(Node node) {
		node.setreduced();
		if (node.isStmt)
			return;
		FactorNode deffactor = (node.getdedge()).getfactor();
		List<Node> pulist = deffactor.getpunodes();
		for (Node n : pulist) {
			mark_reduce(n);
		}
		deffactor.getstmt().setreduced();
	}

	private void path_reduce() {
		for (Node n : nodes) {
			if (n.getobs()) {
				mark_reduce(n);
			}
		}
		// maybe won't be used?
		for (Node n : stmts) {
			if (n.getobs()) {
				mark_reduce(n);
			}
		}
	}

	// TODO: how to slice in the graph to optimize the procedure, though the stmt
	// result can be cut as the mark
	private void node_reduce() {

	}

	public long bp_inference() {
		long startTime = System.currentTimeMillis();
		path_reduce();

		boolean outreduced = true;
		if (outreduced) {
			System.out.println("\nreduced Nodes: ");
			for (Node n : stmts) {
				if (n.getreduced())
					n.print();
			}
			for (Node n : nodes) {
				if (n.getreduced())
					n.print();
			}
			System.out.println("\n");
		}

		for (int i = 0; i < bp_times; i++) {
			boolean isend = true;
			for (FactorNode n : factornodes) {
				n.send_message();
			}
			for (Node n : nodes) {
				if (n.send_message())
					isend = false;
			}
			for (Node n : stmts) {
				if (n.send_message())
					isend = false;
			}
			if (isend) {
				System.out.println("\n\n" + i + "\n\n");
				System.out.println("\n\n" + i + "\n\n");
				break;
			}
		}
		nodes.sort(new Comparator<Node>() {
			@Override
			public int compare(Node arg0, Node arg1) {
				return Double.compare(arg0.bp_getprob(), arg1.bp_getprob());
			}
		});
		stmts.sort(new Comparator<Node>() {
			@Override
			public int compare(Node arg0, Node arg1) {
				return Double.compare(arg0.bp_getprob(), arg1.bp_getprob());
			}
		});
		long endTime = System.currentTimeMillis();

		return (endTime - startTime);

	}

	public StmtNode getTopStmt() {// should be called after inference()
		return stmts.get(0);
	}

	public List<StmtNode> getTopkStmt(int k) {
		return stmts.subList(0, k);
	}

	public void merge(SimpleProbGraph oth) {
		return;// TODO
	}

	public void printgraph() {
		System.out.println("\nNodes: ");
		for (Node n : stmts) {
			n.print();
		}
		for (Node n : nodes) {
			n.print();
		}
		System.out.println("Factors:");
		for (FactorNode n : factornodes) {
			n.print();
		}
	}

	public void printprobs() {
		System.out.println("\nProbabilities: ");
		System.out.println("Vars:" + nodes.size());
		for (Node n : nodes) {
			n.printprob();
		}
		System.out.println("Stmts:" + stmts.size());
		for (StmtNode n : stmts) {
			n.printprob();
		}
	}

	public void bp_printprobs() {
		System.out.println("\nProbabilities: ");
		System.out.println("Vars:" + nodes.size());
		for (Node n : nodes) {
			n.bpPrintProb();
		}
		System.out.println("Stmts:" + stmts.size());
		for (StmtNode n : stmts) {
			n.bpPrintProb();
		}
	}

	private double getdiff(double a, double b) {
		double max = Math.max(Math.abs(a), Math.abs(b));
		return max == 0.0 ? 0 : Math.abs(a - b) / max;
	}

	public void check_bp(boolean verbose) {
		long bptime = this.bp_inference();

		System.out.println("\nProbabilities: ");
		System.out.println("Vars:" + nodes.size());
		int cnt = 0;
		for (Node n : nodes) {
			if (!verbose) {
				cnt++;
				if (cnt > 10)
					break;
			}
			n.bpPrintProb();
		}
		System.out.println("Stmts:" + stmts.size());
		for (StmtNode n : stmts) {
			n.bpPrintProb();
		}
		System.out.println("Belief propagation time : " + bptime / 1000.0 + "s");
	}

	public double check_bp_with_bf(boolean verbose) {
		double maxdiff = 0;
		String diffname = null;
		double maxdiffstmt = 0;
		String diffnamestmt = null;
		long bftime = this.bf_inference();
		long bptime = this.bp_inference();
		if (verbose) {
			System.out.println("\nProbabilities: ");
			System.out.println("Vars:" + nodes.size());
		}
		for (Node n : nodes) {
			if (verbose) {
				n.printprob();
				n.bpPrintProb();
			}
			if (!n.obs) {
				double diff = getdiff(n.bp_getprob(), n.getprob());
				if (diff > maxdiff) {
					maxdiff = diff;
					diffname = n.getName();
				}

			}
		}
		if (verbose)
			System.out.println("Stmts:" + stmts.size());
		for (StmtNode n : stmts) {
			if (verbose) {
				n.printprob();
				n.bpPrintProb();
			}
			if (!n.obs) {
				double diff = getdiff(n.bp_getprob(), n.getprob());
				if (diff > maxdiffstmt) {
					maxdiffstmt = diff;
					diffnamestmt = n.getName();
				}
			}
		}
		if (verbose) {
			System.out.println("Var max relative difference:" + maxdiff + " at " + diffname);
			System.out.println("Stmt max relative difference:" + maxdiffstmt + " at " + diffnamestmt);
			System.out.println("Brute force time : " + bftime / 1000.0 + "s");
			System.out.println("Belief propagation time : " + bptime / 1000.0 + "s");
		}

		return maxdiff;

	}

	// deprecated.
	public void observe(String s, boolean v) {
		String name = getNodeName(s);
		boolean valid = false;
		for (Node n : nodes) {
			if (n.getName().equals(name)) {
				valid = true;
				System.out.println("Node observed as " + v);
				n.observe(v);
			}
		}
		for (Node n : stmts) {
			if (n.getName().equals(s)) {
				valid = true;
				System.out.println("Stmt observed as " + v);
				n.observe(v);
			}
		}
		if (!valid) {
			System.out.println("Invalid Observe");
		}
	}

	private static String readFileToString(String filePath) {
		StringBuilder fileData = new StringBuilder(1000);
		BufferedReader reader;
		try {
			reader = new BufferedReader(new FileReader(filePath));
			char[] buf = new char[10];
			int numRead = 0;
			while ((numRead = reader.read(buf)) != -1) {
				String readData = String.valueOf(buf, 0, numRead);
				fileData.append(readData);
				buf = new char[1024];
			}
			reader.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return fileData.toString();
	}

}
