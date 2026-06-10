// =============================================================================
// Apache Camel Model/DI Separation Type Definitions
// =============================================================================

// --- EIP processor types ----------------------------------------------------

/**
 * Every Apache Camel EIP processor type understood by the model engine.
 * Shared by the modeler (editing) and the read-only canvas (rendering) so both
 * speak the same vocabulary.
 */
export type EipType =
	// Basic
	| 'from' | 'to' | 'toD'
	// Routing
	| 'choice' | 'filter' | 'split' | 'aggregate' | 'multicast'
	| 'recipientList' | 'routingSlip' | 'dynamicRouter' | 'loadBalance' | 'loop'
	// Transformation
	| 'log' | 'setBody' | 'setHeader' | 'setHeaders' | 'setProperty' | 'setVariable'
	| 'removeHeader' | 'removeHeaders' | 'transform' | 'marshal' | 'unmarshal' | 'convertBodyTo'
	// Error Handling
	| 'onException' | 'doTry' | 'doCatch' | 'doFinally'
	// Control Flow
	| 'delay' | 'throttle' | 'stop' | 'process' | 'bean' | 'circuitBreaker' | 'threads' | 'step'
	// Merge (visual-only, not output to XML)
	| 'merge'
	// Others
	| 'wireTap' | 'enrich' | 'pollEnrich' | 'script' | 'validate' | 'saga';

// --- Utility ---
export function generateUUID(): string {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
        return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

// --- Geometry ---
export interface Point { x: number; y: number; }
export interface Bounds { x: number; y: number; width: number; height: number; }

// --- Semantic Layer (Logical Camel Model) ---

/**
 * Camel Processor (Node)
 * From, To, Choice, Log, etc.
 */
export interface CamelProcessorSemantic {
    id: string;
    type: string;           // 'from', 'to', 'choice', 'log', etc.
    parentId?: string;      // 親コンテナ（ChoiceやDoTryなど）のID
    
    // Camel固有のプロパティ（BPMNより柔軟な構造にします）
    properties: {
        uri?: string;       // from/to 用
        simple?: string;    // setBody/log 用
        expression?: string; 
        language?: string;  // simple, xpath, jsonpath...
        [key: string]: any; // その他の動的なプロパティ
    };

    // 拡張表示用フラグ
    isExpanded?: boolean;   // コンテナの場合の開閉状態
}

/**
 * Camel Flow (Edge)
 * ノード間の接続。BPMNのSequenceFlowに相当。
 */
export interface CamelFlowSemantic {
    id: string;
    sourceRef: string;
    targetRef: string;

    // 条件分岐のメタデータ（ChoiceのWhen/Otherwiseなど）
    conditionType?: 'default' | 'when' | 'otherwise';
    expression?: string;    // Whenの場合の条件式
    language?: string;

    // doTry用のフロー役割（try/catch/finally）
    role?: 'try' | 'catch' | 'finally';
    // catch用の例外クラスリスト
    exceptions?: string[];
}

// --- DI Layer (Visual Representation) ---

export interface CamelDiShape {
    id: string;
    semanticId: string;     // 参照するProcessorのID
    bounds: Bounds;
    isExpanded?: boolean;   // 表示上の開閉状態
}

export interface CamelDiEdge {
    id: string;
    semanticId: string;     // 参照するFlowのID
    waypoints: Point[];
}

// --- Store ---

export class CamelModelStore {
    // データ保持用Map
    private semantics: Map<string, CamelProcessorSemantic> = new Map();
    private flows: Map<string, CamelFlowSemantic> = new Map();
    private shapes: Map<string, CamelDiShape> = new Map();
    private edges: Map<string, CamelDiEdge> = new Map();

    // インデックス（検索高速化）
    private shapesBySemantic: Map<string, string[]> = new Map();
    private edgesBySemantic: Map<string, string[]> = new Map();
    private childrenByParent: Map<string, string[]> = new Map();

    // --- Semantic (Nodes) ---
    addProcessor(proc: CamelProcessorSemantic) {
        this.semantics.set(proc.id, proc);
        this.updateChildrenIndex(proc.id, undefined, proc.parentId);
    }

    getProcessor(id: string) { return this.semantics.get(id); }
    
    getAllProcessors() { return Array.from(this.semantics.values()); }

    updateProcessor(id: string, changes: Partial<CamelProcessorSemantic>) {
        const existing = this.semantics.get(id);
        if (!existing) return;
        
        const oldParent = existing.parentId;
        const newParent = changes.parentId;
        
        Object.assign(existing, changes);
        
        if (oldParent !== newParent) {
            this.updateChildrenIndex(id, oldParent, newParent);
        }
    }

    removeProcessor(id: string) {
        const proc = this.semantics.get(id);
        if (!proc) return;
        
        // 子要素も削除（再帰）
        const children = this.getChildren(id);
        children.forEach(c => this.removeProcessor(c.id));

        // 関連するShape/Edge/Flowの削除
        this.updateChildrenIndex(id, proc.parentId, undefined);
        this.semantics.delete(id);
        
        // 接続されているフローも削除
        const relatedFlows = Array.from(this.flows.values()).filter(f => f.sourceRef === id || f.targetRef === id);
        relatedFlows.forEach(f => this.removeFlow(f.id));

        // Shape削除
        const shapeIds = this.shapesBySemantic.get(id) || [];
        shapeIds.forEach(sid => this.shapes.delete(sid));
        this.shapesBySemantic.delete(id);
    }

    getChildren(parentId: string) {
        const ids = this.childrenByParent.get(parentId) || [];
        return ids.map(id => this.semantics.get(id)!).filter(Boolean);
    }

    // --- Flow (Edges) ---
    addFlow(flow: CamelFlowSemantic) { this.flows.set(flow.id, flow); }
    getFlow(id: string) { return this.flows.get(id); }
    getAllFlows() { return Array.from(this.flows.values()); }
    
    removeFlow(id: string) {
        this.flows.delete(id);
        const edgeIds = this.edgesBySemantic.get(id) || [];
        edgeIds.forEach(eid => this.edges.delete(eid));
        this.edgesBySemantic.delete(id);
    }

    // --- DI (Shapes/Edges) ---
    addShape(shape: CamelDiShape) {
        this.shapes.set(shape.id, shape);
        this.addToIndex(this.shapesBySemantic, shape.semanticId, shape.id);
    }
    getShape(id: string) { return this.shapes.get(id); }
    getAllShapes() { return Array.from(this.shapes.values()); }
    
    getShapeForSemantic(semanticId: string): CamelDiShape | undefined {
        const ids = this.shapesBySemantic.get(semanticId);
        return ids && ids.length > 0 ? this.shapes.get(ids[0]) : undefined;
    }

    addEdge(edge: CamelDiEdge) {
        this.edges.set(edge.id, edge);
        this.addToIndex(this.edgesBySemantic, edge.semanticId, edge.id);
    }
    getAllEdges() { return Array.from(this.edges.values()); }

    updateShape(id: string, changes: Partial<CamelDiShape>) {
        const s = this.shapes.get(id);
        if (s) Object.assign(s, changes);
    }

    // --- Helpers ---
    private addToIndex(map: Map<string, string[]>, key: string, val: string) {
        if (!map.has(key)) map.set(key, []);
        map.get(key)!.push(val);
    }

    private updateChildrenIndex(childId: string, oldParent: string | undefined, newParent: string | undefined) {
        if (oldParent) {
            const list = this.childrenByParent.get(oldParent);
            if (list) {
                const idx = list.indexOf(childId);
                if (idx > -1) list.splice(idx, 1);
            }
        }
        if (newParent) {
            this.addToIndex(this.childrenByParent, newParent, childId);
        }
    }

    // 全クリア
    clear() {
        this.semantics.clear();
        this.flows.clear();
        this.shapes.clear();
        this.edges.clear();
        this.shapesBySemantic.clear();
        this.edgesBySemantic.clear();
        this.childrenByParent.clear();
    }
}