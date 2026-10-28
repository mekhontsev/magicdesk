#ifndef MD_DESCRIPTOR_CAPTURE_H
#define MD_DESCRIPTOR_CAPTURE_H
struct md_fd_capture_frame;
struct md_task_call_arguments;
struct md_descriptor_capture_protocol {
    unsigned admit,ready,sent,observed;
    long complete;
    const struct md_task_call_arguments *replay;
};
/* Task-affine export of exact open descriptions under the owner's table turn.
 * The enclosing operation supplies its typed control protocol. */
long md_descriptor_capture_inputs(long,struct md_fd_capture_frame *,const int *,unsigned,
    const struct md_descriptor_capture_protocol *);
#endif
