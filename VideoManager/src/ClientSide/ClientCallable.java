package ClientSide;

import CommonPackage.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

//这是CLICallable的图形界面版，逻辑没有改变，只是加入了图形控件的输出
public class ClientCallable implements Callable<Integer> {
	
	/*
	 * 这些变量都要接收上级函数传值，而call方法本身无法接收参数
	 * ，所以在此定义成员变量，使用构造方法传值。
	 * */
	private String serverIP = null;
	private int serverPort = -1;
	private int requestCode = -1;
	private int mode = -1;
	private String category = null;
	
	private int videoDisplayStart = 0;//起始行数从0计
	private int videoDisplayStep = 9;
	
	private DefaultListModel<String> categoryListModel = null;
	private JPanel mainPanel = null;
	
	/*
	 * 不再使用造型转换，转而使用setter将数据注入
	 */
	public void setMainPanel(JPanel mainPanel) {
		this.mainPanel = mainPanel;
	}
	
	/*获取分类用*/
	public ClientCallable(String serverIP, int serverPort, int requestCode
			,int mode, DefaultListModel<String> categoryListModel) {
		this.serverIP = serverIP;
		this.serverPort = serverPort;
		this.requestCode = requestCode;
		this.mode = mode;
		this.categoryListModel = categoryListModel;
	}
	/*播放视频用，具体的视频信息可以通过取读SelectBlock全局类来得到*/
	public ClientCallable(String serverIP, int serverPort, int requestCode) {
		this.serverIP = serverIP;
		this.serverPort = serverPort;
		this.requestCode = requestCode;
	}
	/*刷新视频列表用*/
	public ClientCallable(String serverIP, int serverPort, int requestCode,
			int mode, String category,int videoDisplayStart,int videoDisplayStep) {
		this.serverIP = serverIP;
		this.serverPort = serverPort;
		this.requestCode = requestCode;
		this.mode = mode;
		this.category = category;
		this.videoDisplayStart = videoDisplayStart;
		this.videoDisplayStep = videoDisplayStep;
	}

	@Override
	public Integer call() throws Exception {
		/*套接字和原生输入输出流*/
		Socket socketToServer = null;
		InputStream inputStream = null;
		OutputStream outputStream =null;
		/*包装后的输入输出流*/
		ObjectInputStream objectInputStream = null;
		BufferedReader readFromServer = null;
		PrintWriter printToServer = null;
		/*线程对象*/
		FFplayCallable ffplayCallable = null;
		Future<Integer> ffplayFuture = null;
		
		VideoInfo videoInfo = null;//获取本显示块内的视频信息数据结构
		String fileID = null;//文件名
		String extension = null;//扩展名
		String location = null;//相对纯路径（不包括文件名）
		String fileRelativePath = null;//完成的相对路径（包括文件名）
		
		try {
			//点播不需要建立连接
			if(requestCode != DefineConstant.ACTION_PLAYVOD){
				// 客户端暂时不用设置SO_REUSEADDR
				socketToServer = new Socket(serverIP, serverPort);
				// 打开输入输出流
				inputStream = socketToServer.getInputStream();
				outputStream = socketToServer.getOutputStream();
				// auto flush
				printToServer = new PrintWriter(outputStream, true);
			}
			/*
			 * 根据请求的功能类型，发送的信息域数量也不同
			 * */
			/*接受到的服务端应答信息*/
			String response = null;
			/*要发送的请求*/
			String request = null;
			/*被选择的块，由静态全局方法和变量得到*/
			DisplayBlock selectedVideoBlock = null;
			
			switch (requestCode) {
			
			case DefineConstant.ACTION_GETCATEGORY:
				/* 请求格式：reqCode | mode */
				request = DefineConstant.ACTION_GETCATEGORY+"|"+mode;
				printToServer.println(request);//发送请求，获取分类
				/*打开反序列化输入流，
				这时服务端已经得到了categorySet并准备发给客户端*/
				objectInputStream = new ObjectInputStream(inputStream);
				ArrayList<String> categorySet = 
								(ArrayList<String>)objectInputStream.readObject();
				for(Iterator<String> iter = categorySet.iterator();iter.hasNext();){
					String categoryElement = iter.next();//不要把iter.next()直接放入事件调度线程，会出现异常的
					SwingUtilities.invokeLater(new Runnable() {
						@Override
						public void run() {
							categoryListModel.addElement(categoryElement);
						}
					});
				}
				break;

			case DefineConstant.ACTION_REFRESHVIDEOLIST:
				
				if(videoDisplayStart == 0)//起点是0，禁止上翻
					Client.ProhibitPreviousPage();
				else Client.AllowPreviousPage();
				
				/*请求格式：req|mode|cate|start|step*/
				request = requestCode+"|"+mode+"|"+category+"|"
						+videoDisplayStart+"|"+videoDisplayStep;
				printToServer.println(request);//发送请求
				/*打开反序列化输入流*/
				objectInputStream = new ObjectInputStream(inputStream);
				/*读取对象个数,这里是用的Integer对象来传送，因为对象流和普通流不能混用
				所以我们就统一用对象流来输入*/
				int count = (Integer)objectInputStream.readObject();
				
				if(count < videoDisplayStep)//查询到末尾了，数据量不足一页，禁止下翻
					Client.ProhibitNextPage();
				else Client.AllowNextPage();
				
				/*count是循环接收对象的个数，不可直接用videoDisplayStep，
				因为实际查到的个数可能小于videoDisplayStep*/
				for(;count != 0;count --){
					videoInfo = (VideoInfo)objectInputStream.readObject();
					DisplayBlock displayBlock = new DisplayBlock(videoInfo);
					SwingUtilities.invokeLater(new Runnable() {
						@Override
						public void run() {
							mainPanel.add(displayBlock);
							mainPanel.revalidate();
							//mainPanel.repaint();//添加组件不许要调用repaint
						}
					});
				}
				break;
			case DefineConstant.ACTION_PLAYLIVE:
				/*因为视频列表刷新时，已经用mode和category进行了过滤，
				 * 所以看到的都是符合要求的，所以播放视频时只需要获取哪个显示块被选中了，
				 * 然后找到所对应视频的路径+文件名即可
				 * */
				/*只需告诉服务端请求码+(视频相对路径+视频文件名)*/
				selectedVideoBlock = SelectBlock.getSelectedBlock();//获得被选视频块
				
				videoInfo = selectedVideoBlock.getVideoInfo();//获取本显示块内的视频信息数据结构
				fileID = videoInfo.getFileID();//文件ID
				extension = videoInfo.getExtension();//扩展名
				location = videoInfo.getLocation();//文件位置
				fileRelativePath = location+fileID+"."+extension;//拼凑相对路径
				
				/*请求格式：req|fileRelativePath，
				 * 服务端会再加上前缀拼凑出文件绝对路径送给ffmpeg
				 * 客户端只需要知道流的名字即可*/
				request = DefineConstant.ACTION_PLAYLIVE+"|"+fileRelativePath;
				printToServer.println(request);//发送请求
				String streamName = null;
				//读取服务器发来的视频流名字，本次接收不需要发送心跳应答
				readFromServer = new BufferedReader(new InputStreamReader(inputStream));
				streamName = readFromServer.readLine();
				while ((response = readFromServer.readLine()) != null){
				//如果持续接收到WAIT信息，说明服务端ffmpeg还没还是发送数据帧
					if(Integer.valueOf(response) == DefineConstant.WAIT){
						printToServer.println("I am alive.");//该字符串任意
						continue;	
					}else{//收到OK消息，跳出循环
						printToServer.println("I am alive.");
						break;
					}
				}
				//根据服务端的指示，现在可以开启ffplay放视频了
				ffplayCallable = new FFplayCallable("rtsp://"
									+serverIP+"/live/"+streamName);
				ffplayFuture = Executors.newSingleThreadExecutor().submit(ffplayCallable);
				//播放器线程已经启动，现在继续收发消息，让服务器知道客户端还活着
				while ((response = readFromServer.readLine()) != null){
					//*检测ffplay进程所在的线程是否已经结束，如果是，那么本线程也就没有继续执行的必要了
					if (ffplayFuture.isDone()) break;
					printToServer.println("I am alive.");
				}
				/*
				 * 注意，上面的循环正常结束的原因就是服务器不再发送数据，可能是视频传输完毕，
				 * 也可能服务器因为未知原因异常终止，但是这种情况基本不会出现。
				 * 如果服务端视频播放完毕，那么上面的循环会跳出，本线程结束，
				 * 但是播放线程是独立的，他不会收到影响,ffplay又要等到用户关闭才会消失，
				 * ，所以出现的现象是ffplay画面静止了，等待用户关闭之后播放线程死亡，全过程终止。
				 */
				break;
			case DefineConstant.ACTION_PLAYVOD:
				/*点播功能不需要再给服务端发消息了，直接干*/
				selectedVideoBlock = SelectBlock.getSelectedBlock();//获得被选视频块
				videoInfo = selectedVideoBlock.getVideoInfo();//获取本显示块内的视频信息数据结构
				fileID = videoInfo.getFileID();//文件ID
				extension = videoInfo.getExtension();//扩展名
				location = videoInfo.getLocation();//文件位置
				fileRelativePath = location+fileID+"."+extension;//拼凑相对路径
				ffplayCallable = new FFplayCallable("rtsp://"
						+serverIP+"/file/"+fileRelativePath);
				ffplayFuture = Executors.newSingleThreadExecutor().submit(ffplayCallable);
				ffplayFuture.get();//线程call函数的返回值对我们没用，这里指示为了等待播放线程死亡
				break;
			default:
				request = DefineConstant.ACTION_UNDEFINED+"|";
				printToServer.println(request);//发送请求
				break;
			}//switch
		} catch (Exception e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(null, "无法连接服务器", "错误", JOptionPane.ERROR_MESSAGE);
			System.out.println("无法连接服务器"+serverIP+serverPort);
		} finally {
			try {
				if (readFromServer != null)readFromServer.close();
				if (printToServer != null)printToServer.close();
				if (socketToServer != null)socketToServer.close();
				System.out.println("All Has been closed! in GUICallable finally block");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}// function call
}

class FFplayCallable implements Callable<Integer>{

	private String rtspURL = null;
	public FFplayCallable(String url) {
		this.rtspURL = url;
	}
	@Override
	public Integer call() throws Exception {
		//开启ffplay进程播放视频
		try {
			Process pc = null;
			ProcessBuilder pb = null;
			ArrayList<String> command = new ArrayList<>();//命令数组
			String os = System.getProperty("os.name");
			if(os.toLowerCase().startsWith("win")){
				File file = new File("ffplay.exe");//启动程序前先检测存在性
				if(!file.exists()){
					JOptionPane.showMessageDialog(null, "ffplay.exe不存在"
							, "错误", JOptionPane.ERROR_MESSAGE);
					return null;
				}
				command.add("ffplay.exe");
			}
			else{//Linux系统只要有ffplay命令即可，不必非要存在可执行文件
				command.add("ffplay");
			}
			//注意，每个被空格隔开的都算是参数，都要分别追加
			//rtspURL文件名的空格也会被java自动处理好（相当于命令行上用引号把这一串括起来）
			//，但是很遗憾，转发程序不支持文件名空格，所以点播不能播放文件名含有空格的文件
			//直播时播放的是流名字，而ffmpeg可以很好的处理文件名空格，所以直播可以播放文件名含有空格的文件
			command.add(rtspURL);
			command.add("-x");
			command.add("1066");//指定宽度
			command.add("-y");
			command.add("600");//指定高度

			pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			pc = pb.start();
			InputStream inputFFplayStatus = pc.getInputStream();
			BufferedReader readFFplayStatus = new BufferedReader(new InputStreamReader(inputFFplayStatus));
//			StringBuffer stringBuffer = new StringBuffer();
//			stringBuffer.append(cmd[0]+cmd[1]);
			try {
				String tmp_in = null;
				while ((tmp_in = readFFplayStatus.readLine()) != null) {
					System.out.println(tmp_in);
					//stringBuffer.append(tmp_in+"\n");
				}
			} catch (Exception e) {e.printStackTrace();
			}finally {
				if (inputFFplayStatus != null)inputFFplayStatus.close();
				System.out.println("FFplay has finished");
			}
			//pc.waitFor();//如果关闭了ffplay，那么pc进程就死了，这个函数等待是没有意义的。
			pc.destroy();
		} catch (Exception e) {e.printStackTrace();}
		return null;
	}
	
}