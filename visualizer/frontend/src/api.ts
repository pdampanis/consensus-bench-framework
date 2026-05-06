// Small fetch wrapper around the backend API. Kept tiny on purpose - the
// visualizer has a small surface and a full-blown HTTP client would be
// overkill.

export interface BenchmarkRun {
  id: number;
  system: string;
  scenario: string;
  runId: string;
  startedAt: string;
  throughputOps: number | null;
  writeAvgUs: number | null;
  writeP95Us: number | null;
  writeP99Us: number | null;
  writeP999Us: number | null;
  readAvgUs: number | null;
  readP99Us: number | null;
  consistencyResult: string | null;
  clusterSize: number | null;
  workload: string | null;
}

export interface Summary {
  n: number;
  mean: number;
  median: number;
  std: number;
  min: number;
  max: number;
  ci95Low: number;
  ci95High: number;
}

export interface Aggregate {
  system: string;
  scenario: string;
  nRuns: number;
  throughputOps: Summary;
  writeP99Us: Summary;
  writeAvgUs: Summary;
  rawRuns: BenchmarkRun[];
}

export interface CompareAcrossSystems {
  scenario: string;
  systems: Record<string, { throughputOps: Summary; writeP99Us: Summary; nRuns: number }>;
}

async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(path);
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
  return r.json();
}

export const api = {
  listSystems: () => getJson<string[]>("/api/systems"),
  listScenarios: (s: string) => getJson<string[]>(`/api/systems/${s}/scenarios`),
  listRuns: (s: string, sc: string) =>
    getJson<BenchmarkRun[]>(`/api/systems/${s}/scenarios/${sc}/runs`),
  aggregate: (s: string, sc: string) =>
    getJson<Aggregate>(`/api/systems/${s}/scenarios/${sc}/aggregate`),
  compareAcrossSystems: (sc: string) => getJson<CompareAcrossSystems>(`/api/compare/${sc}`),
  listAlgorithms: () => getJson<string[]>("/api/algorithms"),
  getAlgorithm: (name: string) => getJson<AlgorithmSpec>(`/api/algorithms/${name}`),
};

export interface AlgorithmNode {
  id: string;
  label: string;
  role: "leader" | "follower" | "client" | "opportunistic" | "broker";
  x: number;
  y: number;
}

export interface AlgorithmEdge {
  from: string;
  to: string;
  kind?: string;
}

export interface AlgorithmStep {
  from?: string;
  to?: string;
  label?: string;
  type?: string;
  node?: string;
  state?: string;
}

export interface AlgorithmScenario {
  id: string;
  title: string;
  description: string;
  steps: AlgorithmStep[];
}

export interface AlgorithmSpec {
  name: string;
  family: "CFT" | "BFT";
  summary: string;
  nodes: AlgorithmNode[];
  edges: AlgorithmEdge[];
  scenarios: AlgorithmScenario[];
}
