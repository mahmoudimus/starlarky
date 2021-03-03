def Error(Exception):
    """
    Base class for all future-related exceptions.
    """
def CancelledError(Error):
    """
    The Future was cancelled.
    """
def TimeoutError(Error):
    """
    The operation exceeded the given deadline.
    """
def InvalidStateError(Error):
    """
    The operation is not allowed in this state.
    """
def _Waiter(object):
    """
    Provides the event that wait() and as_completed() block on.
    """
    def __init__(self):
        """
        Used by as_completed().
        """
    def __init__(self):
        """
        Used by wait(return_when=FIRST_COMPLETED).
        """
    def add_result(self, future):
        """
        Used by wait(return_when=FIRST_EXCEPTION and ALL_COMPLETED).
        """
    def __init__(self, num_pending_calls, stop_on_exception):
        """
        A context manager that does an ordered acquire of Future conditions.
        """
    def __init__(self, futures):
        """
        Invalid return condition: %r
        """
def _yield_finished_futures(fs, waiter, ref_collect):
    """

        Iterate on the list *fs*, yielding finished futures one by one in
        reverse order.
        Before yielding a future, *waiter* is removed from its waiters
        and the future is removed from each set in the collection of sets
        *ref_collect*.

        The aim of this function is to avoid keeping stale references after
        the future is yielded and before the iterator resumes.
    
    """
def as_completed(fs, timeout=None):
    """
    An iterator over the given futures that yields each as it completes.

        Args:
            fs: The sequence of Futures (possibly created by different Executors) to
                iterate over.
            timeout: The maximum number of seconds to wait. If None, then there
                is no limit on the wait time.

        Returns:
            An iterator that yields the given Futures as they complete (finished or
            cancelled). If any given Futures are duplicated, they will be returned
            once.

        Raises:
            TimeoutError: If the entire result iterator could not be generated
                before the given timeout.
    
    """
def wait(fs, timeout=None, return_when=ALL_COMPLETED):
    """
    Wait for the futures in the given sequence to complete.

        Args:
            fs: The sequence of Futures (possibly created by different Executors) to
                wait upon.
            timeout: The maximum number of seconds to wait. If None, then there
                is no limit on the wait time.
            return_when: Indicates when this function should return. The options
                are:

                FIRST_COMPLETED - Return when any future finishes or is
                                  cancelled.
                FIRST_EXCEPTION - Return when any future finishes by raising an
                                  exception. If no future raises an exception
                                  then it is equivalent to ALL_COMPLETED.
                ALL_COMPLETED -   Return when all futures finish or are cancelled.

        Returns:
            A named 2-tuple of sets. The first set, named 'done', contains the
            futures that completed (is finished or cancelled) before the wait
            completed. The second set, named 'not_done', contains uncompleted
            futures.
    
    """
def Future(object):
    """
    Represents the result of an asynchronous computation.
    """
    def __init__(self):
        """
        Initializes the future. Should not be called by clients.
        """
    def _invoke_callbacks(self):
        """
        'exception calling callback for %r'
        """
    def __repr__(self):
        """
        '<%s at %#x state=%s raised %s>'
        """
    def cancel(self):
        """
        Cancel the future if possible.

                Returns True if the future was cancelled, False otherwise. A future
                cannot be cancelled if it is running or has already completed.
        
        """
    def cancelled(self):
        """
        Return True if the future was cancelled.
        """
    def running(self):
        """
        Return True if the future is currently executing.
        """
    def done(self):
        """
        Return True of the future was cancelled or finished executing.
        """
    def __get_result(self):
        """
        Attaches a callable that will be called when the future finishes.

                Args:
                    fn: A callable that will be called with this future as its only
                        argument when the future completes or is cancelled. The callable
                        will always be called by a thread in the same process in which
                        it was added. If the future has already completed or been
                        cancelled then the callable will be called immediately. These
                        callables are called in the order that they were added.
        
        """
    def result(self, timeout=None):
        """
        Return the result of the call that the future represents.

                Args:
                    timeout: The number of seconds to wait for the result if the future
                        isn't done. If None, then there is no limit on the wait time.

                Returns:
                    The result of the call that the future represents.

                Raises:
                    CancelledError: If the future was cancelled.
                    TimeoutError: If the future didn't finish executing before the given
                        timeout.
                    Exception: If the call raised then that exception will be raised.
        
        """
    def exception(self, timeout=None):
        """
        Return the exception raised by the call that the future represents.

                Args:
                    timeout: The number of seconds to wait for the exception if the
                        future isn't done. If None, then there is no limit on the wait
                        time.

                Returns:
                    The exception raised by the call that the future represents or None
                    if the call completed without raising.

                Raises:
                    CancelledError: If the future was cancelled.
                    TimeoutError: If the future didn't finish executing before the given
                        timeout.
        
        """
    def set_running_or_notify_cancel(self):
        """
        Mark the future as running or process any cancel notifications.

                Should only be used by Executor implementations and unit tests.

                If the future has been cancelled (cancel() was called and returned
                True) then any threads waiting on the future completing (though calls
                to as_completed() or wait()) are notified and False is returned.

                If the future was not cancelled then it is put in the running state
                (future calls to running() will return True) and True is returned.

                This method should be called by Executor implementations before
                executing the work associated with this future. If this method returns
                False then the work should not be executed.

                Returns:
                    False if the Future was cancelled, True otherwise.

                Raises:
                    RuntimeError: if this method was already called or if set_result()
                        or set_exception() was called.
        
        """
    def set_result(self, result):
        """
        Sets the return value of work associated with the future.

                Should only be used by Executor implementations and unit tests.
        
        """
    def set_exception(self, exception):
        """
        Sets the result of the future as being the given exception.

                Should only be used by Executor implementations and unit tests.
        
        """
def Executor(object):
    """
    This is an abstract base class for concrete asynchronous executors.
    """
    def submit(*args, **kwargs):
        """
        Submits a callable to be executed with the given arguments.

                Schedules the callable to be executed as fn(*args, **kwargs) and returns
                a Future instance representing the execution of the callable.

                Returns:
                    A Future representing the given call.
        
        """
    def map(self, fn, *iterables, timeout=None, chunksize=1):
        """
        Returns an iterator equivalent to map(fn, iter).

                Args:
                    fn: A callable that will take as many arguments as there are
                        passed iterables.
                    timeout: The maximum number of seconds to wait. If None, then there
                        is no limit on the wait time.
                    chunksize: The size of the chunks the iterable will be broken into
                        before being passed to a child process. This argument is only
                        used by ProcessPoolExecutor; it is ignored by
                        ThreadPoolExecutor.

                Returns:
                    An iterator equivalent to: map(func, *iterables) but the calls may
                    be evaluated out-of-order.

                Raises:
                    TimeoutError: If the entire result iterator could not be generated
                        before the given timeout.
                    Exception: If fn(*args) raises for any values.
        
        """
        def result_iterator():
            """
             reverse to keep finishing order

            """
    def shutdown(self, wait=True):
        """
        Clean-up the resources associated with the Executor.

                It is safe to call this method several times. Otherwise, no other
                methods can be called after this one.

                Args:
                    wait: If True then shutdown will not return until all running
                        futures have finished executing and the resources used by the
                        executor have been reclaimed.
        
        """
    def __enter__(self):
        """

            Raised when a executor has become non-functional after a severe failure.
    
        """