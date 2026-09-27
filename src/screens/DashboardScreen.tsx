// @ts-nocheck
import React, { useState, useCallback, useEffect } from 'react';
import { View, Text, TouchableOpacity, RefreshControl } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { MotiView } from 'moti';
import { ShieldAlert, ShieldCheck, Mic, Clock, Settings, PhoneCall, User, Activity } from 'lucide-react-native';
import { useProtection } from '../context/ProtectionContext';
import { getHistory, DetectionEvent } from '../lib/historyStorage';
import { getVoiceProfiles } from '../lib/profileStorage';
import { ScreenContainer } from '../components/ScreenContainer';
import { Card } from '../components/Card';
import { EmptyState } from '../components/EmptyState';
import { theme } from '../constants/theme';

export default function DashboardScreen({ navigation }: any) {
  const { isProtectionActive } = useProtection();
  const [refreshing, setRefreshing] = useState(false);
  const [history, setHistory] = useState<DetectionEvent[]>([]);
  const [profileCount, setProfileCount] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [userName, setUserName] = useState('');

  const loadData = async () => {
    try {
      const data = await getHistory();
      setHistory(data);
      const profiles = await getVoiceProfiles();
      setProfileCount(profiles.length);
      const name = await AsyncStorage.getItem('user_name');
      setUserName(name || 'User');
    } catch (error) {
      console.error(error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    const unsubscribe = navigation.addListener('focus', () => {
      loadData();
    });
    return unsubscribe;
  }, [navigation]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, []);

  const stats = {
    screened: history.length,
    blocked: history.filter(h => h.isThreat).length,
    profiles: profileCount
  };

  return (
    <ScreenContainer 
      scrollViewProps={{
        refreshControl: <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={theme.colors.accentTeal} />
      }}
    >
      {/* Header */}
      <MotiView 
        from={{ opacity: 0, translateY: -20 }}
        animate={{ opacity: 1, translateY: 0 }}
        transition={{ type: 'timing', duration: 500 }}
        style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: theme.spacing.xl }}
      >
        <View>
          <Text style={[theme.typography.caption, { textTransform: 'uppercase', letterSpacing: 1 }]}>WELCOME BACK,</Text>
          <Text style={[theme.typography.display, { marginTop: 4 }]}>{userName.split(' ')[0]}</Text>
        </View>
        <TouchableOpacity 
          style={{ width: 48, height: 48, backgroundColor: theme.colors.surfaceElevated, borderRadius: theme.borderRadius.full, alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: theme.colors.border }}
          onPress={() => navigation.navigate('Settings')}
        >
          <User color={theme.colors.accentTeal} size={24} />
        </TouchableOpacity>
      </MotiView>

      {/* Protection Status Card */}
      <MotiView
        from={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ type: 'timing', duration: 600, delay: 100 }}
      >
        <Card style={{ borderColor: isProtectionActive ? theme.colors.accentTeal : theme.colors.border }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: theme.spacing.lg }}>
            <View style={{ width: 48, height: 48, borderRadius: theme.borderRadius.full, backgroundColor: isProtectionActive ? `${theme.colors.accentTeal}20` : theme.colors.surfaceElevated, alignItems: 'center', justifyContent: 'center' }}>
              {isProtectionActive ? <ShieldCheck color={theme.colors.accentTeal} size={28} /> : <ShieldAlert color={theme.colors.textDisabled} size={28} />}
            </View>
            <View style={{ marginLeft: theme.spacing.md, flex: 1 }}>
              <Text style={theme.typography.heading}>
                Protection {isProtectionActive ? 'Active' : 'Inactive'}
              </Text>
              <Text style={[theme.typography.caption, { marginTop: 2 }]}>
                {isProtectionActive ? 'Real-time voice analysis running' : 'Tap to enable call monitoring'}
              </Text>
            </View>
          </View>
          <TouchableOpacity 
            style={{ 
              paddingVertical: theme.spacing.md, 
              borderRadius: theme.borderRadius.md, 
              alignItems: 'center', 
              backgroundColor: isProtectionActive ? theme.colors.accentTeal : 'transparent',
              borderWidth: 1,
              borderColor: isProtectionActive ? theme.colors.accentTeal : theme.colors.border
            }}
            onPress={() => navigation.navigate('Protection')}
          >
            <Text style={{ color: isProtectionActive ? '#000000' : theme.colors.textPrimary, fontWeight: '700', fontSize: 16 }}>
              {isProtectionActive ? 'Manage Protection' : 'Start Protection'}
            </Text>
          </TouchableOpacity>
        </Card>
      </MotiView>

      {/* Quick Stats Row */}
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: theme.spacing.md, marginBottom: theme.spacing.xl }}>
        <MotiView from={{ opacity: 0, translateY: 10 }} animate={{ opacity: 1, translateY: 0 }} transition={{ type: 'timing', delay: 200 }} style={{ flex: 1 }}>
          <Card style={{ alignItems: 'center', padding: theme.spacing.md, marginBottom: 0 }}>
            <Text style={[theme.typography.display, { color: theme.colors.accentTeal }]}>{stats.screened}</Text>
            <Text style={[theme.typography.caption, { marginTop: theme.spacing.xs }]}>Screened</Text>
          </Card>
        </MotiView>
        <MotiView from={{ opacity: 0, translateY: 10 }} animate={{ opacity: 1, translateY: 0 }} transition={{ type: 'timing', delay: 300 }} style={{ flex: 1 }}>
          <Card style={{ alignItems: 'center', padding: theme.spacing.md, marginBottom: 0 }}>
            <Text style={[theme.typography.display, { color: theme.colors.dangerRed }]}>{stats.blocked}</Text>
            <Text style={[theme.typography.caption, { marginTop: theme.spacing.xs }]}>Blocked</Text>
          </Card>
        </MotiView>
        <MotiView from={{ opacity: 0, translateY: 10 }} animate={{ opacity: 1, translateY: 0 }} transition={{ type: 'timing', delay: 400 }} style={{ flex: 1 }}>
          <Card style={{ alignItems: 'center', padding: theme.spacing.md, marginBottom: 0 }}>
            <Text style={[theme.typography.display, { color: theme.colors.textPrimary }]}>{stats.profiles}</Text>
            <Text style={[theme.typography.caption, { marginTop: theme.spacing.xs }]}>Profiles</Text>
          </Card>
        </MotiView>
      </View>

      {/* Quick Actions Grid */}
      <Text style={[theme.typography.heading, { marginBottom: theme.spacing.md }]}>Quick Actions</Text>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: theme.spacing.md, marginBottom: theme.spacing.xl }}>
        <TouchableOpacity 
          onPress={() => navigation.navigate('Train')} 
          style={{ flex: 1, backgroundColor: theme.colors.surface, borderRadius: theme.borderRadius.lg, padding: theme.spacing.md, alignItems: 'center', justifyContent: 'center', minHeight: 110, borderWidth: 1, borderColor: theme.colors.border }}
        >
          <View style={{ width: 48, height: 48, borderRadius: theme.borderRadius.full, backgroundColor: `${theme.colors.accentTeal}20`, alignItems: 'center', justifyContent: 'center', marginBottom: theme.spacing.sm }}>
            <Mic color={theme.colors.accentTeal} size={24} />
          </View>
          <Text style={[theme.typography.caption, { fontWeight: '600', color: theme.colors.textPrimary }]}>Train Voice</Text>
        </TouchableOpacity>
        <TouchableOpacity 
          onPress={() => navigation.navigate('History')} 
          style={{ flex: 1, backgroundColor: theme.colors.surface, borderRadius: theme.borderRadius.lg, padding: theme.spacing.md, alignItems: 'center', justifyContent: 'center', minHeight: 110, borderWidth: 1, borderColor: theme.colors.border }}
        >
          <View style={{ width: 48, height: 48, borderRadius: theme.borderRadius.full, backgroundColor: `${theme.colors.successGreen}20`, alignItems: 'center', justifyContent: 'center', marginBottom: theme.spacing.sm }}>
            <Clock color={theme.colors.successGreen} size={24} />
          </View>
          <Text style={[theme.typography.caption, { fontWeight: '600', color: theme.colors.textPrimary }]}>History</Text>
        </TouchableOpacity>
        <TouchableOpacity 
          onPress={() => navigation.navigate('Settings')} 
          style={{ flex: 1, backgroundColor: theme.colors.surface, borderRadius: theme.borderRadius.lg, padding: theme.spacing.md, alignItems: 'center', justifyContent: 'center', minHeight: 110, borderWidth: 1, borderColor: theme.colors.border }}
        >
          <View style={{ width: 48, height: 48, borderRadius: theme.borderRadius.full, backgroundColor: theme.colors.surfaceElevated, alignItems: 'center', justifyContent: 'center', marginBottom: theme.spacing.sm }}>
            <Settings color={theme.colors.textSecondary} size={24} />
          </View>
          <Text style={[theme.typography.caption, { fontWeight: '600', color: theme.colors.textPrimary }]}>Settings</Text>
        </TouchableOpacity>
      </View>

      {/* Recent Activity */}
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: theme.spacing.md }}>
        <Text style={theme.typography.heading}>Recent Activity</Text>
        {history.length > 0 && (
          <TouchableOpacity onPress={() => navigation.navigate('History')}>
            <Text style={{ color: theme.colors.accentTeal, fontWeight: '600', fontSize: 14 }}>View All</Text>
          </TouchableOpacity>
        )}
      </View>

      {isLoading ? (
        <View style={{ paddingVertical: theme.spacing.xxl, alignItems: 'center' }}>
          <Text style={theme.typography.caption}>Loading activity...</Text>
        </View>
      ) : history.length === 0 ? (
        <EmptyState
          icon={<Activity color={theme.colors.textDisabled} size={48} />}
          title="No recent activity"
          description="Your analyzed calls and threats will appear here once protection is active."
        />
      ) : (
        <View style={{ gap: theme.spacing.md }}>
          {history.slice(0, 3).map((item, index) => (
            <MotiView
              key={item.id}
              from={{ opacity: 0, translateX: -10 }}
              animate={{ opacity: 1, translateX: 0 }}
              transition={{ type: 'timing', duration: 400, delay: 500 + index * 100 }}
            >
              <Card style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', padding: theme.spacing.md, marginBottom: 0 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1, paddingRight: theme.spacing.sm }}>
                  <View style={{ width: 40, height: 40, borderRadius: theme.borderRadius.full, alignItems: 'center', justifyContent: 'center', marginRight: theme.spacing.md, backgroundColor: item.isThreat ? `${theme.colors.dangerRed}20` : `${theme.colors.successGreen}20` }}>
                    <PhoneCall color={item.isThreat ? theme.colors.dangerRed : theme.colors.successGreen} size={20} />
                  </View>
                  <View style={{ flex: 1 }}>
                    <Text style={[theme.typography.body, { fontWeight: '600', marginBottom: 2 }]} numberOfLines={1}>
                      {item.context}
                    </Text>
                    <Text style={theme.typography.caption}>{item.timestamp}</Text>
                  </View>
                </View>
                <View style={{ paddingHorizontal: 12, paddingVertical: 4, borderRadius: theme.borderRadius.full, backgroundColor: item.isThreat ? `${theme.colors.dangerRed}20` : item.verdict === 'uncertain' || item.verdict === 'unavailable' ? `${theme.colors.warningAmber}20` : `${theme.colors.successGreen}20` }}>
                  <Text style={{ fontSize: 12, fontWeight: '700', color: item.isThreat ? theme.colors.dangerRed : item.verdict === 'uncertain' || item.verdict === 'unavailable' ? theme.colors.warningAmber : theme.colors.successGreen }}>
                    {item.verdictLabel}
                  </Text>
                </View>
              </Card>
            </MotiView>
          ))}
        </View>
      )}
    </ScreenContainer>
  );
}
