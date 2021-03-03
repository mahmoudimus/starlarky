def SortKey(str, Enum):
    """
    'calls'
    """
    def __new__(cls, *values):
        """
        This class is used for creating reports from data generated by the
            Profile class.  It is a "friend" of that class, and imports data either
            by direct access to members of Profile class, or by reading in a dictionary
            that was emitted (via marshal) from the Profile class.

            The big change from the previous Profiler (in terms of raw functionality)
            is that an "add()" method has been provided to combine Stats from
            several distinct profile runs.  Both the constructor and the add()
            method now take arbitrarily many file names as arguments.

            All the print methods now take an argument that indicates how many lines
            to print.  If the arg is a floating point number between 0 and 1.0, then
            it is taken as a decimal percentage of the available lines to be printed
            (e.g., .1 means print 10% of all available lines).  If it is an integer,
            it is taken to mean the number of lines of data that you wish to have
            printed.

            The sort_stats() method now processes some additional options (i.e., in
            addition to the old -1, 0, 1, or 2 that are respectively interpreted as
            'stdname', 'calls', 'time', and 'cumulative').  It takes either an
            arbitrary number of quoted strings or SortKey enum to select the sort
            order.

            For example sort_stats('time', 'name') or sort_stats(SortKey.TIME,
            SortKey.NAME) sorts on the major key of 'internal function time', and on
            the minor key of 'the name of the function'.  Look at the two tables in
            sort_stats() and get_sort_arg_defs(self) for more examples.

            All methods return self, so you can string together commands like:
                Stats('foo', 'goo').strip_dirs().sort_stats('calls').\
                                    print_stats(5).print_callers(5)
    
        """
    def __init__(self, *args, stream=None):
        """
         calc only if needed
        """
    def load_stats(self, arg):
        """
        'rb'
        """
    def get_top_level_stats(self):
        """
        jprofile
        """
    def add(self, *arg_list):
        """
        Write the profile data to a file we know how to load back.
        """
    def get_sort_arg_defs(self):
        """
        Expand all abbreviations that are unique.
        """
    def sort_stats(self, *field):
        """
         Be compatible with old profiler

        """
    def reverse_order(self):
        """
        ******************************************************************
         The following functions support actual printing of reports
        ******************************************************************

         Optional "amount" is either a line count, or a percentage of lines.


        """
    def eval_print_amount(self, sel, list, msg):
        """
           <Invalid regular expression %r>\n
        """
    def get_print_list(self, sel_list):
        """
           Ordered by: 
        """
    def print_stats(self, *amount):
        """
        ' '
        """
    def print_callees(self, *amount):
        """
        called...
        """
    def print_callers(self, *amount):
        """
        was called by...
        """
    def print_call_heading(self, name_size, column_title):
        """
        Function 
        """
    def print_call_line(self, name_size, source, call_dict, arrow="->"):
        """
        ' '
        """
    def print_title(self):
        """
        '   ncalls  tottime  percall  cumtime  percall'
        """
    def print_line(self, func):  # hack: should print percentages
        """
         hack: should print percentages
        """
def TupleComp:
    """
    This class provides a generic function for comparing any two tuples.
        Each instance records a list of tuple-indices (from most significant
        to least significant), and sort direction (ascending or decending) for
        each tuple-index.  The compare functions can then be used as the function
        argument to the system sort() function when a list of tuples need to be
        sorted in the instances order.
    """
    def __init__(self, comp_select_list):
        """
        **************************************************************************
         func_name is a triple (file:string, line:int, name:string)


        """
def func_strip_path(func_name):
    """
     match what old profile produced
    """
def add_func_stats(target, source):
    """
    Add together all the stats for two profile entries.
    """
def add_callers(target, source):
    """
    Combine two caller lists in a single list.
    """
def count_calls(callers):
    """
    Sum the caller statistics to get total number of calls received.
    """
def f8(x):
    """
    %8.3f
    """
    def ProfileBrowser(cmd.Cmd):
    """
    % 
    """
        def generic(self, fn, line):
            """
            Fraction argument must be in [0, 1]
            """
        def generic_help(self):
            """
            Arguments may be:
            """
        def do_add(self, line):
            """
            Failed to load statistics for %s: %s
            """
        def help_add(self):
            """
            Add profile info from given file to current statistics object.
            """
        def do_callees(self, line):
            """
            'print_callees'
            """
        def help_callees(self):
            """
            Print callees statistics from the current stat object.
            """
        def do_callers(self, line):
            """
            'print_callers'
            """
        def help_callers(self):
            """
            Print callers statistics from the current stat object.
            """
        def do_EOF(self, line):
            """

            """
        def help_EOF(self):
            """
            Leave the profile browser.
            """
        def do_quit(self, line):
            """
            Leave the profile browser.
            """
        def do_read(self, line):
            """
            ':'
            """
        def help_read(self):
            """
            Read in profile data from a specified file.
            """
        def do_reverse(self, line):
            """
            No statistics object is loaded.
            """
        def help_reverse(self):
            """
            Reverse the sort order of the profiling report.
            """
        def do_sort(self, line):
            """
            No statistics object is loaded.
            """
        def help_sort(self):
            """
            Sort profile data according to specified keys.
            """
        def complete_sort(self, text, *args):
            """
            'print_stats'
            """
        def help_stats(self):
            """
            Print statistics from the current stat object.
            """
        def do_strip(self, line):
            """
            No statistics object is loaded.
            """
        def help_strip(self):
            """
            Strip leading path information from filenames in the report.
            """
        def help_help(self):
            """
            Show help for a given command.
            """
        def postcmd(self, stop, line):
            """
            Welcome to the profile statistics browser.
            """