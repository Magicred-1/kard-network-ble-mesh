import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  TextInput,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  Alert,
} from 'react-native';
import { BleMesh, Peer, Message } from '@magicred-1/ble-mesh';

export default function App() {
  const [isConnected, setIsConnected] = useState(false);
  const [nickname, setNickname] = useState('');
  const [peers, setPeers] = useState<Peer[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [messageText, setMessageText] = useState('');
  const [myPeerId, setMyPeerId] = useState('');

  useEffect(() => {
    // Set up event listeners
    const unsubPeers = BleMesh.onPeerListUpdated(({ peers }) => {
      setPeers(peers);
    });

    const unsubMessages = BleMesh.onMessageReceived(({ message }) => {
      setMessages(prev => [...prev, message]);
    });

    const unsubState = BleMesh.onConnectionStateChanged(({ state, peerCount }) => {
      setIsConnected(state === 'connected');
    });

    const unsubError = BleMesh.onError(({ code, message }) => {
      Alert.alert('Error', `${code}: ${message}`);
    });

    return () => {
      unsubPeers();
      unsubMessages();
      unsubState();
      unsubError();
      BleMesh.stop();
    };
  }, []);

  const handleStart = async () => {
    try {
      await BleMesh.start({ nickname: nickname || 'User' });
      const peerId = await BleMesh.getMyPeerId();
      setMyPeerId(peerId);
      setIsConnected(true);
    } catch (error) {
      Alert.alert('Error', 'Failed to start mesh service');
    }
  };

  const handleStop = async () => {
    await BleMesh.stop();
    setIsConnected(false);
    setPeers([]);
    setMessages([]);
  };

  const handleSendMessage = async () => {
    if (!messageText.trim()) return;

    try {
      await BleMesh.sendMessage(messageText);
      setMessages(prev => [
        ...prev,
        {
          id: Date.now().toString(),
          content: messageText,
          senderPeerId: myPeerId,
          senderNickname: nickname || 'Me',
          timestamp: Date.now(),
          isPrivate: false,
        },
      ]);
      setMessageText('');
    } catch (error) {
      Alert.alert('Error', 'Failed to send message');
    }
  };

  const handleSendPrivate = async (peerId: string) => {
    Alert.prompt('Private Message', 'Enter your message:', async text => {
      if (text) {
        try {
          await BleMesh.sendPrivateMessage(text, peerId);
        } catch (error) {
          Alert.alert('Error', 'Failed to send private message');
        }
      }
    });
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>BLE Mesh Chat</Text>

      {!isConnected ? (
        <View style={styles.setupContainer}>
          <TextInput
            style={styles.input}
            placeholder="Enter your nickname"
            value={nickname}
            onChangeText={setNickname}
          />
          <TouchableOpacity style={styles.button} onPress={handleStart}>
            <Text style={styles.buttonText}>Connect to Mesh</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <View style={styles.chatContainer}>
          <View style={styles.header}>
            <Text style={styles.headerText}>
              Connected as {nickname} ({myPeerId.slice(0, 8)}...)
            </Text>
            <TouchableOpacity onPress={handleStop}>
              <Text style={styles.disconnectText}>Disconnect</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.peersContainer}>
            <Text style={styles.sectionTitle}>
              Nearby Peers ({peers.length})
            </Text>
            <FlatList
              horizontal
              data={peers}
              keyExtractor={item => item.peerId}
              renderItem={({ item }) => (
                <TouchableOpacity
                  style={[
                    styles.peerChip,
                    item.isConnected && styles.peerChipConnected,
                  ]}
                  onPress={() => handleSendPrivate(item.peerId)}
                >
                  <Text style={styles.peerChipText}>{item.nickname}</Text>
                </TouchableOpacity>
              )}
            />
          </View>

          <View style={styles.messagesContainer}>
            <Text style={styles.sectionTitle}>Messages</Text>
            <FlatList
              data={messages}
              keyExtractor={item => item.id}
              renderItem={({ item }) => (
                <View
                  style={[
                    styles.messageItem,
                    item.senderPeerId === myPeerId && styles.myMessage,
                    item.isPrivate && styles.privateMessage,
                  ]}
                >
                  <Text style={styles.messageSender}>
                    {item.senderNickname}
                    {item.isPrivate && ' (private)'}
                  </Text>
                  <Text style={styles.messageContent}>{item.content}</Text>
                </View>
              )}
            />
          </View>

          <View style={styles.inputContainer}>
            <TextInput
              style={styles.messageInput}
              placeholder="Type a message..."
              value={messageText}
              onChangeText={setMessageText}
              onSubmitEditing={handleSendMessage}
            />
            <TouchableOpacity style={styles.sendButton} onPress={handleSendMessage}>
              <Text style={styles.sendButtonText}>Send</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
    paddingTop: 50,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 20,
    color: '#333',
  },
  setupContainer: {
    padding: 20,
    alignItems: 'center',
  },
  input: {
    width: '100%',
    padding: 15,
    backgroundColor: '#fff',
    borderRadius: 10,
    marginBottom: 15,
    fontSize: 16,
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 15,
    borderRadius: 10,
    width: '100%',
    alignItems: 'center',
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  chatContainer: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 15,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  headerText: {
    fontSize: 14,
    color: '#666',
  },
  disconnectText: {
    color: '#FF3B30',
    fontWeight: '600',
  },
  peersContainer: {
    padding: 15,
    backgroundColor: '#fff',
    marginBottom: 10,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666',
    marginBottom: 10,
  },
  peerChip: {
    backgroundColor: '#e0e0e0',
    paddingHorizontal: 15,
    paddingVertical: 8,
    borderRadius: 20,
    marginRight: 10,
  },
  peerChipConnected: {
    backgroundColor: '#34C759',
  },
  peerChipText: {
    color: '#333',
    fontWeight: '500',
  },
  messagesContainer: {
    flex: 1,
    padding: 15,
  },
  messageItem: {
    backgroundColor: '#fff',
    padding: 12,
    borderRadius: 10,
    marginBottom: 10,
    maxWidth: '80%',
  },
  myMessage: {
    alignSelf: 'flex-end',
    backgroundColor: '#007AFF',
  },
  privateMessage: {
    borderLeftWidth: 3,
    borderLeftColor: '#FF9500',
  },
  messageSender: {
    fontSize: 12,
    color: '#666',
    marginBottom: 4,
  },
  messageContent: {
    fontSize: 16,
    color: '#333',
  },
  inputContainer: {
    flexDirection: 'row',
    padding: 15,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
  },
  messageInput: {
    flex: 1,
    backgroundColor: '#f5f5f5',
    padding: 12,
    borderRadius: 20,
    marginRight: 10,
    fontSize: 16,
  },
  sendButton: {
    backgroundColor: '#007AFF',
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 20,
    justifyContent: 'center',
  },
  sendButtonText: {
    color: '#fff',
    fontWeight: '600',
  },
});
