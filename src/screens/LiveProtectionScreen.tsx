import React, { useState, useEffect, useCallback } from 'react';
import { View, Text, TouchableOpacity, Switch, DeviceEventEmitter, ScrollView } from 'react-native';
import { MotiView } from 'moti';
import { ShieldAlert, ShieldCheck, Info, Power, AlertTriangle, Smartphone, Bell, Battery, Mic, Phone } from 'lucide-react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useProtection } from '../context/ProtectionContext';
import { OverlayBridge } from '../lib/OverlayBridge';
import { ScreenContainer } from '../components/ScreenContainer';
import { Card } from '../components/Card';
import { theme } from '../constants/theme';
import { 
  checkAllPermissions, PermissionStatus, 
  requestMicrophone, requestPhoneState, 
  requestNotifications, requestOverlay, 
  requestNotificationListener, requestBatteryExemption 
} from '../lib/PermissionsManager';

export default function LiveProtectionScreen() {
  const { isProtectionActive, setIsProtectionActive } = useProtection();
  const [permissions, setPermissions] = useState<PermissionStatus | null>(null);
  const [liveResult, setLiveResult] = useState<{ verdict: string; score: number | null; evidence: number; reason: string | null } | null>(null);
  const navigation = useNavigation();

  useFocusEffect(
    useCallback(() => {
      checkPerms();
    }, [])
  );

  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener('onDetectionUpdate', (event: any) => {
      if (event.event === 'onDetectionUpdate' && event.payload) {
        try {
          const parsed = typeof event.payload === 'string' ? JSON.parse(event.payload) : event.payload;
          setLiveResult({
            verdict: parsed.verdict,
            score: parsed.score ?? null,
            evidence: parsed.evidence ?? 0,
            reason: parsed.reason ?? null,
          });
        } catch (e) {
          setLiveResult(null);
        }
      } else if (event.event === 'onCaptureStopped' || event.event === 'onSpeakerRequired') {
        setLiveResult(null);
      }
    });

    return () => {
      subscription.remove();
    };
  }, []);

  const checkPerms = async () => {
    const status = await checkAllPermissions();
    setPermissions(status);
  };

  const allGranted = permissions && 
    permissions.microphone && 
    permissions.phoneState && 
    permissions.notifications && 
    permissions.overlay && 
    permissions.notificationListener && 
    permissions.battery;

  const handleToggleProtection = async () => {
    if (isProtectionActive) {
      await OverlayBridge.stopProtection();
      setIsProtectionActive(false);
      setLiveResult(null);
    } else {
      if (!allGranted) {
        // We gate starting protection on having all permissions
        return;
      }
      await OverlayBridge.startProtection();
      setIsProtectionActive(true);
    }
  };

  const verdictColor = (verdict: string) => {
    if (verdict === 'cloned') return theme.colors.dangerRed;
    if (verdict === 'suspicious') return theme.colors.warningAmber;
    if (verdict === 'real') return theme.colors.successGreen;
    return theme.colors.textSecondary;
  };

  const verdictLabel = (verdict: string) => {
    switch (verdict) {
      case 'real': return 'Genuine voice';
      case 'suspicious': return 'Suspicious';
      case 'cloned': return 'Cloned voice';
      case 'unavailable': return 'Unavailable';
      default: return 'Uncertain';
    }
  };

  return (
    <ScreenContainer>
      <ScrollView showsVerticalScrollIndicator={false}>
        <MotiView
          from={{ opacity: 0, translateY: -20 }}
          animate={{ opacity: 1, translateY: 0 }}
          transition={{ type: 'timing', duration: 500 }}
          style={{ marginBottom: theme.spacing.xl, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}
        >
          <View style={{ flex: 1, marginRight: theme.spacing.md }}>
            <Text style={theme.typography.display}>Live Protection</Text>
          </View>
          <View style={{ backgroundColor: `${theme.colors.accentTeal}20`, borderColor: theme.colors.accentTeal, borderWidth: 1, borderRadius: theme.borderRadius.full, paddingHorizontal: 12, paddingVertical: 6, flexDirection: 'row', alignItems: 'center' }}>
            <Smartphone color={theme.colors.accentTeal} size={14} />
            <Text style={{ color: theme.colors.accentTeal, fontSize: 12, fontWeight: '700', marginLeft: 6 }}>On-Device</Text>
          </View>
        </MotiView>

        <MotiView
          from={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ type: 'timing', duration: 400 }}
        >
          <Card style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderColor: isProtectionActive ? theme.colors.accentTeal : theme.colors.border, marginBottom: theme.spacing.lg }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1 }}>
              <View style={{ width: 48, height: 48, borderRadius: theme.borderRadius.full, backgroundColor: isProtectionActive ? `${theme.colors.accentTeal}20` : theme.colors.surfaceElevated, alignItems: 'center', justifyContent: 'center', marginRight: theme.spacing.md }}>
                {isProtectionActive ? <ShieldCheck color={theme.colors.accentTeal} size={24} /> : <Power color={theme.colors.textDisabled} size={24} />}
              </View>
              <View style={{ flex: 1 }}>
                <Text style={theme.typography.heading}>{isProtectionActive ? 'Protection Active' : 'Live Protection'}</Text>
                <Text style={[theme.typography.caption, { marginTop: 2 }]}>{isProtectionActive ? 'Call monitoring is enabled' : 'Real-time deepfake detection'}</Text>
                {liveResult && (
                  <Text style={{ marginTop: 4, color: verdictColor(liveResult.verdict), fontWeight: '700', fontSize: 12 }}>
                    {verdictLabel(liveResult.verdict)}
                    {liveResult.score !== null ? ` · score ${Math.round(liveResult.score * 100)}` : ''}
                    {liveResult.reason ? ` · ${liveResult.reason}` : ''}
                  </Text>
                )}
                {!allGranted && !isProtectionActive && (
                  <Text style={{ marginTop: 4, color: theme.colors.dangerRed, fontWeight: '700', fontSize: 12 }}>
                    Missing permissions
                  </Text>
                )}
              </View>
            </View>
            <Switch 
              value={isProtectionActive}
              onValueChange={handleToggleProtection}
              disabled={!allGranted && !isProtectionActive}
              trackColor={{ false: theme.colors.textDisabled, true: theme.colors.accentTeal }}
              thumbColor="#FFFFFF"
            />
          </Card>
        </MotiView>

        {permissions && !allGranted && (
          <View style={{ gap: theme.spacing.md }}>
            <Text style={[theme.typography.heading, { marginBottom: theme.spacing.xs }]}>Required Permissions</Text>

            {!permissions.microphone && (
              <PermissionRow icon={<Mic color={theme.colors.dangerRed} size={16} />} title="Microphone" description="Required to analyze audio" action={async () => { await requestMicrophone(); checkPerms(); }} />
            )}
            
            {!permissions.phoneState && (
              <PermissionRow icon={<Phone color={theme.colors.dangerRed} size={16} />} title="Phone State" description="Required to detect active calls" action={async () => { await requestPhoneState(); checkPerms(); }} />
            )}

            {!permissions.notifications && (
              <PermissionRow icon={<Bell color={theme.colors.dangerRed} size={16} />} title="Notifications" description="Required for foreground service" action={async () => { await requestNotifications(); checkPerms(); }} />
            )}

            {!permissions.overlay && (
              <PermissionRow icon={<ShieldAlert color={theme.colors.dangerRed} size={16} />} title="Display Over Apps" description="Required for overlay alerts" action={async () => { await requestOverlay(); setTimeout(checkPerms, 2000); }} />
            )}

            {!permissions.notificationListener && (
              <PermissionRow icon={<Bell color={theme.colors.dangerRed} size={16} />} title="Notification Access" description="Required for VoIP call detection" action={async () => { await requestNotificationListener(); setTimeout(checkPerms, 2000); }} />
            )}

            {!permissions.battery && (
              <PermissionRow icon={<Battery color={theme.colors.warningAmber} size={16} />} color={theme.colors.warningAmber} title="Unrestricted Battery" description="Prevents OS from killing protection" action={async () => { await requestBatteryExemption(); setTimeout(checkPerms, 2000); }} />
            )}
          </View>
        )}

        <View style={{ gap: theme.spacing.md, marginTop: theme.spacing.xl }}>
          <Card>
            <View style={{ flexDirection: 'row', alignItems: 'flex-start' }}>
              <View style={{ width: 40, height: 40, borderRadius: theme.borderRadius.full, backgroundColor: `${theme.colors.accentTeal}20`, alignItems: 'center', justifyContent: 'center', marginRight: theme.spacing.md }}>
                <Info color={theme.colors.accentTeal} size={20} />
              </View>
              <View style={{ flex: 1 }}>
                <Text style={theme.typography.heading}>What's Active</Text>
                <Text style={[theme.typography.body, { color: theme.colors.textSecondary, marginTop: theme.spacing.xs, lineHeight: 22 }]}>
                  VoxSentry is listening for synthetic voice patterns during speakerphone and app calls (like WhatsApp or Telegram).
                </Text>
              </View>
            </View>
          </Card>
  
          <Card style={{ borderColor: theme.colors.warningAmber, backgroundColor: `${theme.colors.warningAmber}05`, marginBottom: theme.spacing.xl }}>
            <View style={{ flexDirection: 'row', alignItems: 'flex-start' }}>
              <View style={{ width: 40, height: 40, borderRadius: theme.borderRadius.full, backgroundColor: `${theme.colors.warningAmber}20`, alignItems: 'center', justifyContent: 'center', marginRight: theme.spacing.md }}>
                <AlertTriangle color={theme.colors.warningAmber} size={20} />
              </View>
              <View style={{ flex: 1 }}>
                <Text style={[theme.typography.heading, { color: theme.colors.warningAmber }]}>Platform Limitation</Text>
                <Text style={[theme.typography.body, { color: theme.colors.textSecondary, marginTop: theme.spacing.xs, lineHeight: 22 }]}>
                  Due to Android OS limitations, standard cellular calls cannot be intercepted directly without being placed on speakerphone.
                </Text>
              </View>
            </View>
          </Card>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}

function PermissionRow({ icon, title, description, action, color = theme.colors.dangerRed }: { icon: React.ReactNode, title: string, description: string, action: () => void, color?: string }) {
  return (
    <MotiView from={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }}>
      <Card style={{ borderColor: color, borderWidth: 1, backgroundColor: `${color}05` }}>
        <View style={{ flexDirection: 'row', alignItems: 'flex-start', marginBottom: 0 }}>
          <View style={{ width: 32, height: 32, borderRadius: theme.borderRadius.full, backgroundColor: `${color}20`, alignItems: 'center', justifyContent: 'center', marginRight: theme.spacing.sm }}>
            {icon}
          </View>
          <View style={{ flex: 1 }}>
            <Text style={theme.typography.body}>{title}</Text>
            <Text style={[theme.typography.caption, { color: color, marginTop: 2 }]}>{description}</Text>
          </View>
          <TouchableOpacity style={{ backgroundColor: color, borderRadius: theme.borderRadius.md, paddingHorizontal: 12, paddingVertical: 6 }} onPress={action}>
            <Text style={{ color: '#FFFFFF', fontWeight: '700', fontSize: 12 }}>Grant</Text>
          </TouchableOpacity>
        </View>
      </Card>
    </MotiView>
  );
}
