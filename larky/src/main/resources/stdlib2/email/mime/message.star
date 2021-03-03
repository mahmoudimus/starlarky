def MIMEMessage(MIMENonMultipart):
    """
    Class representing message/* MIME documents.
    """
    def __init__(self, _msg, _subtype='rfc822', *, policy=None):
        """
        Create a message/* type MIME document.

                _msg is a message object and must be an instance of Message, or a
                derived class of Message, otherwise a TypeError is raised.

                Optional _subtype defines the subtype of the contained message.  The
                default is "rfc822" (this is defined by the MIME standard, even though
                the term "rfc822" is technically outdated by RFC 2822).
        
        """