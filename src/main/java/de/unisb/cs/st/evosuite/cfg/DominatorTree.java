package de.unisb.cs.st.evosuite.cfg;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import de.unisb.cs.st.evosuite.mutation.Mutateable;

/**
 * 
 * Given a CFG this class computes the immediateDominators and the
 * dominatingFrontiers for each CFG vertex
 * 
 * The current algorithm to determine the immediateDominators runs in time
 * O(e*log n) where e is the number of control flow edges and n the number of
 * CFG vertices and is taken from:
 * 
 * "A Fast Algorithm for Finding Dominators in a Flowgraph" THOMAS LENGAUER and
 * ROBERT ENDRE TARJAN 1979, Stanford University
 * 
 * DOI: 10.1145/357062.357071
 * http://portal.acm.org/citation.cfm?doid=357062.357071
 * 
 * 
 * The algorithm for computing the dominatingFrontiers when given the
 * immediateDominators is taken from
 * 
 * "Efficiently Computing Static Single Assignment Form and the Control
 * Dependence Graph" RON CYTRON, JEANNE FERRANTE, BARRY K. ROSEN, and MARK N.
 * WEGMAN IBM Research Division and F. KENNETH ZADECK Brown University 1991
 * 
 * 
 * @author Andre Mis
 */
public class DominatorTree<V extends Mutateable> extends EvoSuiteGraph<DominatorNode<V>> {

	private static Logger logger = Logger.getLogger(DominatorTree.class);
	
	
	private  int nodeCount = 0;
	private  ControlFlowGraph<V> cfg;
	
	private Map<V,DominatorNode<V>> dominatorNodesMap = new HashMap<V,DominatorNode<V>>();
	private  Map<Integer,DominatorNode<V>> dominatorIDMap = new HashMap<Integer,DominatorNode<V>>();
	private Map<V,Set<V>> dominatingFrontiers = new HashMap<V,Set<V>>();

	/**
	 * Will start the computation of all immediateDominators for the given CFG
	 * which can later be retrieved via getImmediateDominator()
	 */
	public DominatorTree(ControlFlowGraph<V> cfg) {

		logger.debug("Computing DominatorTree for "+cfg.getName());
		
		this.cfg = cfg;
		
		createDominatorNodes();

		V root = cfg.determineEntryPoint(); // TODO change to getEntryPoint()
		logger.info("determined root: "+root.getName());
		DominatorNode<V> rootNode = getDominatorNodeFor(root);
		
		depthFirstAnalyze(rootNode);
		
		computeSemiDominators();
		computeImmediateDominators(rootNode);
		
		createDominatorTree();
		
		computeDominatorFrontiers(rootNode);
	}

	private void createDominatorTree() {
		
		// add dominator nodes
		addVertices(dominatorIDMap.values());
		
		logger.debug("DTNodes: "+vertexCount());
		
		// build up tree by adding for each node v an edge from v.iDom to v
		for (DominatorNode<V> v : vertexSet()) {
			if (v.isRootNode()) 
				continue;
			if(addEdge(v.immediateDominator, v) == null)
				throw new IllegalStateException(
						"internal error while building dominator tree edges");
			
			logger.debug("added DTEdge from "+v.immediateDominator.n+" to "+v.n);
		}
		
		logger.debug("DTEdges: "+edgeCount());
		
		// sanity check
		if(isEmpty())
			throw new IllegalStateException("expect dominator trees to not be empty");
		// check tree is connected
		if(!isConnected())
			throw new IllegalStateException("dominator tree expected to be connected");
		// TODO more sanity checks - no one likes to be insane ;)
	}

	private void computeDominatorFrontiers(DominatorNode<V> currentNode) {
		
		// TODO check assumption: exitPoints in original CFG are exitPoints in resulting DominatorTree
		
		for(DominatorNode<V> child : getChildren(currentNode))
			computeDominatorFrontiers(child);
		
		logger.debug("computing dominatingFrontier for: "+currentNode.toString());
		
		Set<V> dominatingFrontier = dominatingFrontiers.get(currentNode);
		if(dominatingFrontier == null)
			dominatingFrontier = new HashSet<V>();

		// "local"
		for(V child : cfg.getChildren(currentNode.node)) {
			DominatorNode<V> y = getDominatorNodeFor(child);
			if(y.immediateDominator.n != currentNode.n) {
				logger.debug("  LOCAL adding to DFs: "+y.node.getName());
				dominatingFrontier.add(y.node);
			}
		}
		
		// "up"
		for(DominatorNode<V> z : getChildren(currentNode))
			for(V y : dominatingFrontiers.get(z.node))
				if(getDominatorNodeFor(y).immediateDominator.n != currentNode.n) {
					logger.debug("  UP adding to DFs: "+y.getName());
					dominatingFrontier.add(y);
				}
		
		dominatingFrontiers.put(currentNode.node, dominatingFrontier);
	}

	/**
	 * Given a node of this objects CFG this method returns it's previously
	 * computed immediateDominator
	 * 
	 * The immediateDominator iDom of a node v has the following properties:
	 * 
	 * 1) iDom dominates v
	 * 
	 * 2) every other dominator of v dominates iDom
	 * 
	 * A node w dominates v or is a dominator of v if and only if every path
	 * from the CFG's entryPoint to v contains w
	 * 
	 * @param v A node within this objects CFG for wich the immediateDominator is to be returned
	 * @return 
	 */
	public V getImmediateDominator(V v) {
		if (v == null)
			throw new IllegalArgumentException("null given");
		DominatorNode<V> domNode = dominatorNodesMap.get(v);
		if (domNode == null)
			throw new IllegalStateException("unknown vertice given");
		
		if(domNode.immediateDominator == null) {
			// sanity check: this is only allowed to happen if v is root of CFG
			if(domNode.n != 1)
				throw new IllegalStateException("expect known node without iDom to be root of CFG");
			
			return null;
		}
		
		return domNode.immediateDominator.node;
	}
	
	public Set<V> getDominatingFrontiers(V v) {
		if(v==null)
			throw new IllegalStateException("null given");
		
		return dominatingFrontiers.get(v);
	}
	
	// computation
	
	private void createDominatorNodes() {
		
		for(V v : cfg.vertexSet())
			dominatorNodesMap.put(v, new DominatorNode<V>(v));
	}

	private void depthFirstAnalyze(DominatorNode<V> currentNode) {
		// step 1
		
		initialize(currentNode);
		
		for(V w : cfg.getChildren(currentNode.node)) {
			DominatorNode<V> wNode = getDominatorNodeFor(w);
			if(wNode.semiDominator == null) {
				wNode.parent = currentNode;
				depthFirstAnalyze(wNode);
			}
		}
	}
	
	private void initialize(DominatorNode<V> currentNode) {
		
		nodeCount++;
		currentNode.n = nodeCount;
		currentNode.semiDominator = currentNode;
		
		logger.debug("created "+currentNode.toString()+" for "+currentNode.node.toString());
		
		dominatorIDMap.put(nodeCount, currentNode);
	}
	
	private void computeSemiDominators() {
		
		for (int i = nodeCount; i >= 2; i--) {
			DominatorNode<V> w = getDominatorNodeById(i);
			
			// step 2
			for (V current : cfg.getParents(w.node)) {
				DominatorNode<V> v = getDominatorNodeFor(current);
				DominatorNode<V> u = v.eval();
				
				if (u.semiDominator.n < w.semiDominator.n)
					w.semiDominator = u.semiDominator;
			}
			
			w.semiDominator.bucket.add(w);
			w.link(w.parent);
			
			// step 3
			while (!w.parent.bucket.isEmpty()) {
				
				DominatorNode<V> v = w.parent.getFromBucket();
				if(!w.parent.bucket.remove(v))
					throw new IllegalStateException("internal error");

				DominatorNode<V> u = v.eval();
				v.immediateDominator = (u.semiDominator.n < v.semiDominator.n ? u
						: w.parent);
			}
		}
	}
	
	private void computeImmediateDominators(DominatorNode<V> rootNode) {
		// step 4
		for(int i = 2; i<=nodeCount;i++) {
			DominatorNode<V> w = getDominatorNodeById(i);
			
			if(w.immediateDominator != w.semiDominator)
				w.immediateDominator = w.immediateDominator.immediateDominator;
			
//			logger.debug("iDom for node "+i+" was: "+w.immediateDominator.n);
		}
		
		rootNode.immediateDominator = null;
	}
	
	private DominatorNode<V> getDominatorNodeById(int id) {
		DominatorNode<V> r = dominatorIDMap.get(id);
		if(r == null)
			throw new IllegalArgumentException("id unknown to this tree");
		
		return r;
	}

	private DominatorNode<V> getDominatorNodeFor(V v) {
		DominatorNode<V> r = dominatorNodesMap.get(v);
		if(r == null)
			throw new IllegalStateException("expect dominatorNodesMap to contain domNodes for all Vs");
		
		return r;
	}
	
	public String getName() {
		return "DominatorTree"+graphId;
	}
}
