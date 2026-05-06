import { useEffect, useMemo, useState } from "react";
import { AlgorithmEdge, AlgorithmNode, AlgorithmStep } from "../api";

interface Props {
  nodes: AlgorithmNode[];
  edges: AlgorithmEdge[];
  steps: AlgorithmStep[];
  /** milliseconds per step */
  stepDurationMs?: number;
}

interface ActivePacket {
  key: string;
  fromX: number;
  fromY: number;
  toX: number;
  toY: number;
  label?: string;
  type?: string;
  startedAt: number;
}

const PACKET_COLORS: Record<string, string> = {
  request: "#a78bfa",
  response: "#10b981",
  replicate: "#38bdf8",
  ack: "#22d3ee",
  election: "#facc15",
  heartbeat: "#60a5fa",
  vote: "#f97316",
  commit: "#10b981",
  metadata: "#94a3b8",
  notify: "#c084fc",
  coordination: "#f472b6",
  forward: "#a3a3a3",
  fetch: "#38bdf8",
};

const ROLE_COLORS: Record<string, string> = {
  leader: "var(--leader)",
  follower: "var(--follower)",
  client: "var(--client)",
  opportunistic: "#34d399",
  broker: "#38bdf8",
};

/**
 * Renders the algorithm's graph as an SVG and animates the scenario steps.
 *
 * Rendering model:
 *   - Each node is a circle at the (x, y) position from the spec.
 *   - Each edge is a translucent line between its two nodes.
 *   - When a step fires, the edge between (from, to) lights up bright, and
 *     a circular "packet" moves from the sender to the receiver over
 *     stepDurationMs milliseconds, labelled with the step's message type.
 *   - For "node_state" steps (e.g. crashed, partitioned, becomes leader),
 *     the node's visual state changes instead of a packet being drawn.
 *
 * The animation is driven by a simple timer that advances the step index
 * at stepDurationMs intervals. When the scenario finishes, the view holds
 * the end-state; clicking Replay on the parent component remounts us and
 * resets.
 */
export default function NetworkCanvas({
  nodes,
  edges,
  steps,
  stepDurationMs = 1200,
}: Props) {
  const [stepIdx, setStepIdx] = useState(0);
  const [nodeStates, setNodeStates] = useState<Record<string, string>>({});
  const [activeEdges, setActiveEdges] = useState<Set<string>>(new Set());
  const [packets, setPackets] = useState<ActivePacket[]>([]);

  const nodeById = useMemo(() => {
    const m: Record<string, AlgorithmNode> = {};
    for (const n of nodes) m[n.id] = n;
    return m;
  }, [nodes]);

  // Reset node visual roles on scenario change (remount via key in parent).
  useEffect(() => {
    const initial: Record<string, string> = {};
    for (const n of nodes) initial[n.id] = n.role;
    setNodeStates(initial);
    setStepIdx(0);
    setActiveEdges(new Set());
    setPackets([]);
  }, [nodes]);

  // Drive the scenario forward on a timer. Each step either fires a packet
  // animation (with a message on an edge) or mutates a node state.
  useEffect(() => {
    if (stepIdx >= steps.length) return;
    const step = steps[stepIdx];
    const now = Date.now();

    if (step.type === "node_state" && step.node && step.state) {
      setNodeStates((s) => ({ ...s, [step.node!]: step.state! }));
    } else if (step.type === "timeout" && step.node) {
      // flash the node briefly
      setNodeStates((s) => ({ ...s, [step.node!]: "timeout" }));
      setTimeout(
        () => setNodeStates((s) => ({ ...s, [step.node!]: nodeById[step.node!]?.role ?? "follower" })),
        stepDurationMs - 200
      );
    } else if (step.from && step.to) {
      const a = nodeById[step.from];
      const b = nodeById[step.to];
      if (a && b) {
        const edgeKey = `${step.from}->${step.to}`;
        setActiveEdges((s) => new Set(s).add(edgeKey));
        setPackets((ps) => [
          ...ps,
          {
            key: `${stepIdx}-${edgeKey}`,
            fromX: a.x,
            fromY: a.y,
            toX: b.x,
            toY: b.y,
            label: step.label,
            type: step.type,
            startedAt: now,
          },
        ]);
        // remove the packet after its animation completes
        setTimeout(() => {
          setPackets((ps) => ps.filter((p) => p.key !== `${stepIdx}-${edgeKey}`));
          setActiveEdges((s) => {
            const n = new Set(s);
            n.delete(edgeKey);
            return n;
          });
        }, stepDurationMs);
      }
    }

    const t = setTimeout(() => setStepIdx((i) => i + 1), stepDurationMs);
    return () => clearTimeout(t);
  }, [stepIdx, steps, nodeById, stepDurationMs]);

  return (
    <div>
      <svg
        viewBox="0 0 800 620"
        style={{ width: "100%", height: "auto", background: "#0f172a", borderRadius: 6 }}
      >
        {/* Edge layer: drawn first so packets render on top */}
        {edges.map((e, i) => {
          const a = nodeById[e.from];
          const b = nodeById[e.to];
          if (!a || !b) return null;
          const isActive =
            activeEdges.has(`${e.from}->${e.to}`) || activeEdges.has(`${e.to}->${e.from}`);
          return (
            <line
              key={i}
              x1={a.x}
              y1={a.y}
              x2={b.x}
              y2={b.y}
              stroke={isActive ? "#38bdf8" : "#334155"}
              strokeWidth={isActive ? 3 : 1.5}
              opacity={isActive ? 0.9 : 0.5}
            />
          );
        })}

        {/* Packet animations */}
        {packets.map((p) => (
          <g key={p.key}>
            <circle r={7} fill={PACKET_COLORS[p.type ?? ""] ?? "#38bdf8"}>
              <animate
                attributeName="cx"
                from={p.fromX}
                to={p.toX}
                dur={`${stepDurationMs}ms`}
                fill="freeze"
              />
              <animate
                attributeName="cy"
                from={p.fromY}
                to={p.toY}
                dur={`${stepDurationMs}ms`}
                fill="freeze"
              />
            </circle>
            {p.label && (
              <text
                x={(p.fromX + p.toX) / 2}
                y={(p.fromY + p.toY) / 2 - 8}
                fontSize="11"
                fill="#e2e8f0"
                textAnchor="middle"
                style={{ pointerEvents: "none" }}
              >
                {p.label}
              </text>
            )}
          </g>
        ))}

        {/* Nodes */}
        {nodes.map((n) => {
          const state = nodeStates[n.id] ?? n.role;
          const isCrashed = state === "crashed";
          const isPartitioned = state === "partitioned";
          const isTimeout = state === "timeout";
          const isLeader = state === "leader";
          const fill = isCrashed
            ? "var(--crashed)"
            : isPartitioned
            ? "var(--partitioned)"
            : isLeader
            ? "var(--leader)"
            : ROLE_COLORS[n.role] ?? "var(--follower)";
          return (
            <g key={n.id}>
              <circle
                cx={n.x}
                cy={n.y}
                r={isLeader ? 28 : 24}
                fill={fill}
                stroke={isTimeout ? "#f59e0b" : "#0f172a"}
                strokeWidth={isTimeout ? 3 : 2}
                opacity={isCrashed ? 0.3 : isPartitioned ? 0.4 : 1}
              />
              <text
                x={n.x}
                y={n.y + 4}
                fontSize="11"
                fontWeight="600"
                textAnchor="middle"
                fill="#0f172a"
              >
                {n.id}
              </text>
              <text
                x={n.x}
                y={n.y + 46}
                fontSize="11"
                textAnchor="middle"
                fill="#cbd5e1"
              >
                {n.label}
              </text>
              {isCrashed && (
                <text x={n.x} y={n.y - 34} fontSize="10" textAnchor="middle" fill="var(--err)">
                  ✕ crashed
                </text>
              )}
              {isPartitioned && (
                <text
                  x={n.x}
                  y={n.y - 34}
                  fontSize="10"
                  textAnchor="middle"
                  fill="var(--muted)"
                >
                  ⚠ partitioned
                </text>
              )}
            </g>
          );
        })}
      </svg>

      <div style={{ marginTop: 12, fontSize: 12, color: "var(--muted)" }}>
        Step {Math.min(stepIdx, steps.length)} / {steps.length}
        {stepIdx >= steps.length && " — complete"}
      </div>

      <LegendBar />
    </div>
  );
}

function LegendBar() {
  const items = [
    ["request", "client request"],
    ["replicate", "replication / propose"],
    ["vote", "vote"],
    ["ack", "ack"],
    ["commit", "commit"],
    ["election", "election"],
    ["heartbeat", "heartbeat"],
    ["metadata", "metadata"],
    ["response", "response"],
  ];
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 12, marginTop: 8, fontSize: 11 }}>
      {items.map(([type, label]) => (
        <span
          key={type}
          style={{ display: "inline-flex", alignItems: "center", gap: 6, color: "var(--muted)" }}
        >
          <span
            style={{
              display: "inline-block",
              width: 10,
              height: 10,
              background: PACKET_COLORS[type] ?? "#38bdf8",
              borderRadius: "50%",
            }}
          />
          {label}
        </span>
      ))}
    </div>
  );
}
