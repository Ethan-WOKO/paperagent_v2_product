import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import {
  V2_PRODUCT_AVAILABILITY_FAILED,
  V2_PRODUCT_AVAILABILITY_LOADING,
  isV2CapabilityAvailable,
  isV2ControlDisabled,
  loadV2ProductAvailability,
  parseV2ProductAvailability,
  v2AvailabilityLabel,
} from '../src/utils/v2ProductAvailability';

const enabledDocument = {
  formatVersion: 1,
  enabled: true,
  capabilities: [
    'literature.search',
    'project.read-analysis',
    'project.candidate',
  ],
};

describe('V2 product availability', () => {
  it('reads the single authenticated product capability endpoint', () => {
    const api = readFileSync(new URL('../src/api/agent.ts', import.meta.url), 'utf8');
    expect(api).toContain("http.get<V2ProductAvailabilityDocument>('/agent/sessions/v2/capabilities')");
    expect(api).not.toContain("'/agent/v2/capabilities'");
  });

  it('accepts only the format-1 allowlisted capability document', () => {
    const state = parseV2ProductAvailability(enabledDocument);
    expect(state.status).toBe('ready');
    expect(isV2CapabilityAvailable(state, 'literature.search')).toBe(true);
    expect(isV2CapabilityAvailable(state, 'project.read-analysis')).toBe(true);
    expect(isV2CapabilityAvailable(state, 'project.candidate')).toBe(true);
    expect(() => parseV2ProductAvailability({
      ...enabledDocument,
      capabilities: [...enabledDocument.capabilities, 'legacy.plan'],
    })).toThrow('v2-availability-invalid-capabilities');
    expect(() => parseV2ProductAvailability({
      ...enabledDocument,
      formatVersion: 2,
    })).toThrow('v2-availability-invalid-document');
  });

  it('keeps every V2 control closed when the server disables the product boundary', () => {
    const state = parseV2ProductAvailability({
      ...enabledDocument,
      enabled: false,
    });
    expect(state.status).toBe('ready');
    expect(state.capabilities).toEqual(enabledDocument.capabilities);
    expect(isV2CapabilityAvailable(state, 'literature.search')).toBe(false);
    expect(isV2CapabilityAvailable(state, 'project.read-analysis')).toBe(false);
    expect(v2AvailabilityLabel(state, 'project.candidate')).toBe('V2 unavailable');
  });

  it('fails closed on a read failure or malformed server response', async () => {
    const failedRead = vi.fn().mockRejectedValue(new Error('network details must not escape'));
    await expect(loadV2ProductAvailability(failedRead))
      .resolves.toBe(V2_PRODUCT_AVAILABILITY_FAILED);
    await expect(loadV2ProductAvailability(async () => ({
      enabled: true,
      capabilities: ['literature.search'],
    }))).resolves.toBe(V2_PRODUCT_AVAILABILITY_FAILED);
    expect(isV2CapabilityAvailable(V2_PRODUCT_AVAILABILITY_LOADING, 'literature.search')).toBe(false);
  });

  it('gates each capability independently and terminal idle state unlocks only an available control', () => {
    const literatureOnly = parseV2ProductAvailability({
      formatVersion: 1,
      enabled: true,
      capabilities: ['literature.search'],
    });
    expect(isV2ControlDisabled(literatureOnly, 'literature.search', true)).toBe(true);
    expect(isV2ControlDisabled(literatureOnly, 'literature.search', false)).toBe(false);
    expect(isV2ControlDisabled(literatureOnly, 'project.read-analysis', false)).toBe(true);
    expect(v2AvailabilityLabel(literatureOnly, 'literature.search')).toBe('V2 available');
  });

  it('wires both pages to the capability document without a legacy fallback', () => {
    const chat = readFileSync(new URL('../src/views/ChatPage.vue', import.meta.url), 'utf8');
    const project = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');
    expect(chat).toContain('getV2ProductAvailability');
    expect(chat).toContain("isV2CapabilityAvailable(v2Availability.value, 'literature.search')");
    expect(chat).toContain('if (!v2LiteratureAvailable.value)');
    expect(project).toContain('getV2ProductAvailability');
    expect(project).toContain("isV2CapabilityAvailable(v2Availability.value, 'project.read-analysis')");
    expect(project).toContain("isV2CapabilityAvailable(v2Availability.value, 'project.candidate')");
    expect(project).toContain('if (!v2ProjectAnalysisAvailable.value)');
    expect(project).toContain('if (!v2ProjectCandidateAvailable.value)');
    expect(chat).not.toContain('sendAgentMessage(sessionId, request)');
    expect(project).not.toContain('sendProjectMessage(projectId, sessionId, request)');
  });

  it('aborts scoped V2 work on session/project switch while ordinary controls remain ungated', () => {
    const chat = readFileSync(new URL('../src/views/ChatPage.vue', import.meta.url), 'utf8');
    const project = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');
    expect(chat).toContain('watch(selectedSessionId');
    expect(chat).toContain('stopLiteraturePolling()');
    expect(project).toContain('stopProjectAnalysisPolling()');
    expect(project).toContain('stopProjectCandidatePolling()');
    expect(chat).not.toContain(':disabled="!v2LiteratureAvailable" @click="handleSend"');
    expect(project).not.toContain(':disabled="!v2ProjectAnalysisAvailable" @click="sendChat"');
  });
});
