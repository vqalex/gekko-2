package de.gekko.arbitrager;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.gekko.concurrency.BinarySemaphore;
import de.gekko.exception.CurrencyMismatchException;
import de.gekko.exchanges.BittrexArbitrageExchange;
import de.gekko.websocket.BittrexWebsocket;
import de.gekko.websocket.OrderBookUpdate;
import de.gekko.websocket.Updateable;


public class BittrexStreamingTriangularArbitrager extends TriangularArbitrager implements Runnable, Updateable {
	
	private static final Logger LOGGER = LoggerFactory.getLogger("Arbitrager");
	
	private boolean stop = false;
	private final BinarySemaphore processUpdateSem = new BinarySemaphore(false);
	Map<CurrencyPair, PriorityQueue<OrderBook>> orderBookQueues = new HashMap<>();	
	Map<CurrencyPair, ReentrantLock> locks = new HashMap<>();

	public BittrexStreamingTriangularArbitrager(BittrexArbitrageExchange exchange, CurrencyPair basePair,
			CurrencyPair crossPair1, CurrencyPair crossPair2) throws IOException, CurrencyMismatchException {
		super(exchange, basePair, crossPair1, crossPair2);
		
		BittrexWebsocket bittrexWS = new BittrexWebsocket();
		
		Comparator<OrderBook> byTimeStamp = (orderBook1, orderBook2) -> {
			if(orderBook1.getTimeStamp() != null && orderBook1.getTimeStamp() != null) {
				if(orderBook1.getTimeStamp().before(orderBook2.getTimeStamp())) {
					return 1;
				} else {
					return -1;
				}
			} else {
				return 0;
			}

		};
		
		getCurrencyPairs().forEach(currencyPair -> {
			locks.put(currencyPair, new ReentrantLock());
			orderBookQueues.put(currencyPair, new PriorityQueue<>(byTimeStamp));
			try {
				bittrexWS.registerSubscriber(currencyPair, this);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		});

	}

	/**
	 * Main arbitrage routine.
	 */
	@Override
	public void run() {
		Map<CurrencyPair, OrderBook> orderBooks = new HashMap<>();
		int arbitCounter = 0;
		
		while(!stop) {
			try {
				// wait for updates
				processUpdateSem.acquire();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// lock access to queues for thread safe clearing
			//locks.values().forEach(lock -> lock.lock()); //TODO fein granulareres locking
			orderBookQueues.forEach((currencyPair, queue) -> {
				locks.get(currencyPair).lock();
				// if update is available
				if(queue.peek() != null) {
					// add most recent updated orderBook
					orderBooks.put(currencyPair, queue.poll());
					// clear queue because older updates are irrelevant
					queue.clear();
				}
				locks.get(currencyPair).unlock();
			});
			// editing queues done, release locks
			//locks.values().forEach(lock -> lock.unlock()); //TODO fein granulareres locking
			
			if(orderBooks.containsKey(getBasePair()) && orderBooks.containsKey(getCrossPair1()) && orderBooks.containsKey(getCrossPair2())) {
				try {
					if(triangularArbitrage1(orderBooks.get(getBasePair()), orderBooks.get(getCrossPair1()), orderBooks.get(getCrossPair2()))){
						updateOrderbooks();
						arbitCounter++;
					}
					if(triangularArbitrage2(orderBooks.get(getBasePair()), orderBooks.get(getCrossPair1()), orderBooks.get(getCrossPair2()))){
						arbitCounter++;
					}
				} catch (NotAvailableFromExchangeException | NotYetImplementedForExchangeException | ExchangeException
						| IOException | InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				LOGGER.info("Numer of Arbitrage Chances: {}", arbitCounter);
			}
		}
	}
	
	/**
	 * Thread safe updating of orderbooks.
	 */
	@Override
	public void receiveUpdate(OrderBookUpdate orderBookUpdate) {
		// aquire lock for specific orderbook
		locks.get(orderBookUpdate.getCurrencyPair()).lock();
		// push updated orderbook onto queue
		orderBookQueues.get(orderBookUpdate.getCurrencyPair()).add(orderBookUpdate.getOrderBook());
		// release the aquired lock
		locks.get(orderBookUpdate.getCurrencyPair()).unlock();
		// release update semaphore to start processing updates in processor thread
		processUpdateSem.release();
	}

}