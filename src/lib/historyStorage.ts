import { NativeModules } from 'react-native';

const { CallDetectionModule } = NativeModules;

export type Verdict = 'real' | 'suspicious' | 'cloned' | 'uncertain' | 'unavailable';

export type DetectionEvent = {
  id: string;
  verdict: Verdict;
  verdictLabel: string;
  isThreat: boolean;
  score: number | null;
  scoreLabel: string;
  timestamp: string;
  context: string;
  method: string;
  reason: string | null;
};

type NativeCallRecord = {
  id: string;
  timestamp: number;
  duration: number;
  finalStatus: string;
  maxConfidence: number;
  callType: string;
  reason?: string | null;
};

const VERDICT_LABELS: Record<Verdict, string> = {
  real: 'Genuine',
  suspicious: 'Suspicious',
  cloned: 'Cloned',
  uncertain: 'Uncertain',
  unavailable: 'Unavailable',
};

function normalizeVerdict(status: string): Verdict {
  switch (status) {
    case 'real':
    case 'safe':
      return 'real';
    case 'cloned':
    case 'threat':
      return 'cloned';
    case 'suspicious':
      return 'suspicious';
    case 'unavailable':
      return 'unavailable';
    default:
      return 'uncertain';
  }
}

export const getHistory = async (): Promise<DetectionEvent[]> => {
  try {
    if (!CallDetectionModule) return [];

    const dataStr = await CallDetectionModule.getHistory();
    const nativeRecords: NativeCallRecord[] = JSON.parse(dataStr);

    nativeRecords.sort((a, b) => b.timestamp - a.timestamp);

    return nativeRecords.map((record) => {
      const verdict = normalizeVerdict(record.finalStatus);
      const score = typeof record.maxConfidence === 'number' ? record.maxConfidence : null;
      return {
        id: record.id,
        verdict,
        verdictLabel: VERDICT_LABELS[verdict],
        isThreat: verdict === 'cloned',
        score,
        scoreLabel: score !== null ? `${(score * 100).toFixed(0)}` : 'n/a',
        timestamp: new Date(record.timestamp).toLocaleString([], {
          month: 'short',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
        }),
        context: record.callType === 'whatsapp' ? 'WhatsApp Call' : 'Phone Call',
        method: 'On-device TFLite pipeline',
        reason: record.reason ?? null,
      };
    });
  } catch (e) {
    console.error('Failed to load native history', e);
    return [];
  }
};

export const clearHistory = async () => {
  try {
    if (CallDetectionModule) {
      await CallDetectionModule.clearHistory();
    }
  } catch (e) {
    console.error('Failed to clear native history', e);
  }
};
