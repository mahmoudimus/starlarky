def HelpParser(HTMLParser):
    """
    Render help.html into a text widget.

        The overridden handle_xyz methods handle a subset of html tags.
        The supplied text should have the needed tag configurations.
        The behavior for unsupported tags, such as table, is undefined.
        If the tags generated by Sphinx change, this class, especially
        the handle_starttag and handle_endtags methods, might have to also.
    
    """
    def __init__(self, text):
        """
         Text widget we're rendering into.
        """
    def indent(self, amt=1):
        """
        Change indent (+1, 0, -1) and tags.
        """
    def handle_starttag(self, tag, attrs):
        """
        Handle starttags in help.html.
        """
    def handle_endtag(self, tag):
        """
        Handle endtags in help.html.
        """
    def handle_data(self, data):
        """
        Handle date segments in help.html.
        """
def HelpText(Text):
    """
    Display help.html.
    """
    def __init__(self, parent, filename):
        """
        Configure tags and feed file to parser.
        """
    def findfont(self, names):
        """
        Return name of first font family derived from names.
        """
def HelpFrame(Frame):
    """
    Display html text, scrollbar, and toc.
    """
    def __init__(self, parent, filename):
        """
        'background'
        """
    def toc_menu(self, text):
        """
        Create table of contents as drop-down menu.
        """
def HelpWindow(Toplevel):
    """
    Display frame with rendered html.
    """
    def __init__(self, parent, filename, title):
        """
        WM_DELETE_WINDOW
        """
def copy_strip():
    """
    Copy idle.html to idlelib/help.html, stripping trailing whitespace.

        Files with trailing whitespace cannot be pushed to the git cpython
        repository.  For 3.x (on Windows), help.html is generated, after
        editing idle.rst on the master branch, with
          sphinx-build -bhtml . build/html
          python_d.exe -c "from idlelib.help import copy_strip; copy_strip()"
        Check build/html/library/idle.html, the help.html diff, and the text
        displayed by Help => IDLE Help.  Add a blurb and create a PR.

        It can be worthwhile to occasionally generate help.html without
        touching idle.rst.  Changes to the master version and to the doc
        build system may result in changes that should not changed
        the displayed text, but might break HelpParser.

        As long as master and maintenance versions of idle.rst remain the
        same, help.html can be backported.  The internal Python version
        number is not displayed.  If maintenance idle.rst diverges from
        the master version, then instead of backporting help.html from
        master, repeat the procedure above to generate a maintenance
        version.
    
    """
def show_idlehelp(parent):
    """
    Create HelpWindow; called from Idle Help event handler.
    """