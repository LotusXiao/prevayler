//Prevayler(TM) - The Free-Software Prevalence Layer.
//Copyright (C) 2001-2003 Klaus Wuestefeld
//This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

package org.prevayler.implementation.replication;

import org.prevayler.Clock;
import org.prevayler.foundation.serialization.Serializer;
import org.prevayler.implementation.TransactionCapsule;
import org.prevayler.implementation.TransactionTimestamp;
import org.prevayler.implementation.clock.BrokenClock;
import org.prevayler.implementation.publishing.TransactionPublisher;
import org.prevayler.implementation.publishing.TransactionSubscriber;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Date;


/** Reserved for future implementation.
 */
public class ClientPublisher implements TransactionPublisher {

	private final BrokenClock _clock = new BrokenClock();

	private TransactionSubscriber _subscriber;
	private final Object _upToDateMonitor = new Object();

	private TransactionCapsule _myTransactionCapsule;
	private final Object _myTransactionMonitor = new Object();
	private RuntimeException _myTransactionRuntimeException;
	private Error _myTransactionError;

	private final ObjectOutputStream _toServer;
	private final ObjectInputStream _fromServer;

	private final Serializer _journalSerializer;


	public ClientPublisher(String serverIpAddress, int serverPort, Serializer journalSerializer) throws IOException {
		System.out.println("The replication logic is still under development.");
		_journalSerializer = journalSerializer;
		Socket socket = new Socket(serverIpAddress, serverPort);
		_toServer = new ObjectOutputStream(socket.getOutputStream());   // Get the OUTPUT stream first. JDK 1.3.1_01 for Windows will lock up if you get the INPUT stream first.
		_fromServer = new ObjectInputStream(socket.getInputStream());
		startListening();
	}


	private void startListening() {
		Thread listener = new Thread() {
			public void run() {
				try {
					while (true) receiveTransactionFromServer();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		};
		listener.setDaemon(true);
		listener.start();
	}


	public synchronized void addSubscriber(TransactionSubscriber subscriber, long initialTransaction) throws IOException, ClassNotFoundException {
		if (_subscriber != null) throw new UnsupportedOperationException("The current implementation can only support one subscriber. Future implementations will support more.");
		_subscriber = subscriber;
		synchronized (_upToDateMonitor) {
			_toServer.writeObject(new Long(initialTransaction));
			wait(_upToDateMonitor);
		}
	}


	public void removeSubscriber(TransactionSubscriber subscriber) {
		throw new UnsupportedOperationException("Removing subscribers is not yet supported by the current implementation.");
	}


	public synchronized void publish(TransactionCapsule transactionCapsule) {
		if (_subscriber == null) throw new IllegalStateException("To publish a transaction, this ClientPublisher needs a registered subscriber.");
		synchronized (_myTransactionMonitor) {
			_myTransactionCapsule = transactionCapsule;
			
			try {
				_toServer.writeObject(transactionCapsule);
			} catch (IOException iox) {
				iox.printStackTrace();
				while (true) Thread.yield();
			}
			wait(_myTransactionMonitor);
			
			throwEventualErrors();
		}
	}


	private void throwEventualErrors() throws RuntimeException, Error {
		try {
			if (_myTransactionRuntimeException != null) throw _myTransactionRuntimeException;
			if (_myTransactionError != null) throw _myTransactionError;
		} finally {
			_myTransactionRuntimeException = null;
			_myTransactionError = null;
		}
	}


	private void receiveTransactionFromServer() throws IOException, ClassNotFoundException {
		Object transactionCandidate = _fromServer.readObject();
		
		if (transactionCandidate.equals(ServerConnection.SUBSCRIBER_UP_TO_DATE)) {
			synchronized (_upToDateMonitor) { _upToDateMonitor.notify(); }
			return;
		}

		if (transactionCandidate instanceof RuntimeException) {
			_myTransactionRuntimeException = (RuntimeException)transactionCandidate;
			notifyMyTransactionMonitor();
			return;
		}
		if (transactionCandidate instanceof Error) {
			_myTransactionError = (Error)transactionCandidate;
			notifyMyTransactionMonitor();
			return;
		}

		Date timestamp = (Date)_fromServer.readObject();
		_clock.advanceTo(timestamp);

		if (transactionCandidate.equals(ServerConnection.CLOCK_TICK)) return;

		long systemVersion = _fromServer.readLong();

		if (transactionCandidate.equals(ServerConnection.REMOTE_TRANSACTION)) {
			_subscriber.receive(new TransactionTimestamp(_myTransactionCapsule, systemVersion, timestamp));
			notifyMyTransactionMonitor();
			return;
		}

		TransactionCapsule capsule = ((TransactionCapsule) transactionCandidate).withSerializer(_journalSerializer);
		_subscriber.receive(new TransactionTimestamp(capsule, systemVersion, timestamp));
	}


	private static void wait(Object monitor) {
		try {
			monitor.wait();
		} catch (InterruptedException ix) {
			throw new RuntimeException("Unexpected InterruptedException.");
		}
	}


	private void notifyMyTransactionMonitor() {
		synchronized (_myTransactionMonitor) {
			_myTransactionMonitor.notify();
		}
	}


	public Clock clock() {
		return _clock;
	}


	public void close() throws IOException {
		_fromServer.close();
		_toServer.close();
	}

}
